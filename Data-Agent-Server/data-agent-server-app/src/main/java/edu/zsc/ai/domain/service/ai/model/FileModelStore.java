package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.*;
import java.util.function.*;
import org.springframework.stereotype.Component;

/** Stable OS lock + JVM stripes + reload/check/atomic replace. No data or secret cache. */
@Component
public class FileModelStore {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ReentrantLock[] MUTEXES = new ReentrantLock[128];
    static { Arrays.setAll(MUTEXES, i -> new ReentrantLock()); }
    private final MultiProviderProperties properties;
    private final ThreadLocal<View> view = new ThreadLocal<>();
    private record View(ModelAccessContext scope, FileModelState state) {}
    public FileModelStore(MultiProviderProperties properties) { this.properties=properties; }

    public <T> T read(ModelAccessContext scope, Function<FileModelState,T> action) {
        var current=view.get();
        if(current!=null) {
            if(!StartupModelConfigRepository.sameScope(current.scope(),scope)) throw ModelManagementException.missing();
            return action.apply(current.state());
        }
        return locked(scope,false,action);
    }
    public <T> T update(ModelAccessContext scope, Function<FileModelState,T> action) {
        if(view.get()!=null) throw ModelManagementException.unavailable();
        return locked(scope,true,action);
    }
    public <T> T snapshot(ModelAccessContext scope, Supplier<T> action) {
        return read(scope, ignored -> action.get());
    }
    private <T> T locked(ModelAccessContext scope,boolean write,Function<FileModelState,T> action) {
        if(scope==null || scope.workspaceType()==null || scope.ownerId()<=0) throw ModelManagementException.invalid();
        ReentrantLock mutex=null; boolean held=false;
        try {
            Path directory=directory(scope); Path target=directory.resolve("models.json"),lock=directory.resolve("models.lock");
            mutex=MUTEXES[(lock.hashCode() & Integer.MAX_VALUE)%MUTEXES.length];
            if(!(held=mutex.tryLock(5,TimeUnit.SECONDS))) throw ModelManagementException.unavailable();
            rejectLink(lock); rejectLink(target);
            try(FileChannel channel=FileChannel.open(lock,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ownerOnly(lock,false);
                try(FileLock ignored=acquire(channel)) {
                    var state=load(target,scope); String before=write?ModelManagementJson.write(state):null;
                    view.set(new View(scope,state));
                    try {
                    T result=action.apply(state);
                    if(write && !before.equals(ModelManagementJson.write(state))) {
                        if(state.revision==Long.MAX_VALUE) throw ModelManagementException.unavailable();
                        state.revision++; validate(state,scope);
                        byte[] bytes=ModelManagementJson.write(state).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if(bytes.length>MAX_BYTES) throw ModelManagementException.invalid();
                        save(target,bytes);
                    }
                    return result;
                    } finally { view.remove(); }
                }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw ModelManagementException.unavailable(); }
        catch(IOException | UnsupportedOperationException | IllegalArgumentException | SecurityException e) { throw ModelManagementException.unavailable(); }
        finally { if(held) mutex.unlock(); }
    }
    private FileLock acquire(FileChannel channel) throws IOException,InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {
            var lock=channel.tryLock();if(lock!=null)return lock;
            Thread.sleep(10);
        }while(System.nanoTime()<end);
        throw new IOException("Model configuration lock timeout");
    }
    private Path directory(ModelAccessContext scope) throws IOException {
        String configured=properties.getModelRouting().getStorageDirectory();
        if(configured==null || configured.isBlank())throw ModelManagementException.unavailable();
        Path root=Path.of(configured).normalize();
        if(!root.isAbsolute() || root.getParent()==null)throw ModelManagementException.unavailable();
        rejectLink(root); Files.createDirectories(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        root=root.toRealPath(); ownerOnly(root,true);
        for(String part:List.of(scope.workspaceType().name(),Long.toString(scope.ownerId()))) {
            root=root.resolve(part);rejectLink(root);
            try { Files.createDirectory(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
            catch(FileAlreadyExistsException e) { if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw e; }
            ownerOnly(root,true);
        }
        return root;
    }
    private FileModelState load(Path target,ModelAccessContext scope) throws IOException {
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            var empty=new FileModelState(); empty.workspaceType=scope.workspaceType();empty.ownerId=scope.ownerId();return empty;
        }
        rejectLink(target); if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)>MAX_BYTES) throw ModelManagementException.unavailable();
        ownerOnly(target,false);
        byte[] bytes;
        try(var input=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(MAX_BYTES+1); }
        if(bytes.length>MAX_BYTES) throw ModelManagementException.unavailable();
        String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        var tree=ModelManagementJson.read(json,com.fasterxml.jackson.databind.JsonNode.class);
        if(!tree.path("formatVersion").isIntegralNumber() || !tree.path("formatVersion").canConvertToInt() || tree.path("formatVersion").intValue()!=1
                || !tree.path("revision").isIntegralNumber() || !tree.path("revision").canConvertToLong()
                || !tree.path("ownerId").isIntegralNumber() || !tree.path("ownerId").canConvertToLong()
                || !tree.path("workspaceType").isTextual() || !tree.path("startupImported").isBoolean()) throw ModelManagementException.unavailable();
        for(String field:List.of("credentials","connections","models","bindings","aliases"))if(!tree.path(field).isObject())throw ModelManagementException.unavailable();
        var state=ModelManagementJson.read(json,FileModelState.class); validate(state,scope);return state;
    }
    private void save(Path target,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),".models-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
            }
            rejectLink(target); replace(temporary,target);
            // Directory fsync is not supported by every filesystem; the file itself was synced before atomic rename.
            try(var directory=FileChannel.open(target.getParent(),StandardOpenOption.READ)){directory.force(true);}
            catch(IOException | UnsupportedOperationException ignored) { }
        } finally { Files.deleteIfExists(temporary); }
    }
    /** Separate seam for fault-injection tests; production never falls back to a non-atomic write. */
    protected void replace(Path temporary,Path target) throws IOException { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
    private static void ownerOnly(Path path,boolean directory) throws IOException { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------")); }
    private static void rejectLink(Path path) throws IOException { if(Files.isSymbolicLink(path))throw new IOException("Invalid model storage path"); }
    private static void validate(FileModelState s,ModelAccessContext a) {
        try {
            if(s.formatVersion!=1 || s.revision<0 || s.workspaceType!=a.workspaceType() || s.ownerId!=a.ownerId()
                    || s.credentials==null || s.connections==null || s.models==null || s.bindings==null || s.aliases==null) throw new IllegalArgumentException();
            for(var e:s.credentials.entrySet()) {
                var k=e.getValue();if(k==null || !e.getKey().equals(k.id()) || !ModelResourceId.belongs(k.id(),'k',a) || k.version()<1)throw new IllegalArgumentException();
                if("ENCRYPTED".equals(k.kind())) { if(k.encryptedPayload()==null || k.secretRef()!=null || k.keyVersion()==null || !k.keyVersion().matches("[A-Z0-9_]{1,32}") || Base64.getDecoder().decode(k.encryptedPayload()).length<29)throw new IllegalArgumentException(); }
                else if(!"DEPLOYMENT_REF".equals(k.kind()) || k.encryptedPayload()!=null || k.keyVersion()!=null || k.secretRef()==null
                        || !(k.secretRef().equals("deployment:qwen") || k.secretRef().matches("env:[A-Z][A-Z0-9_]*")))throw new IllegalArgumentException();
            }
            Set<String> codes=new HashSet<>();
            for(var e:s.connections.entrySet()) {
                var c=e.getValue();if(c==null || !e.getKey().equals(c.id()) || !ModelResourceId.belongs(c.id(),'c',a) || c.version()<1 || c.credentialVersion()<0 || !codes.add(c.code()) || c.allowedPrivateHosts()==null)throw new IllegalArgumentException();
                code(c.code());text(c.name(),200);text(c.provider(),64);
                ModelProfiles.connection(c.apiStyle(),c.compatibilityProfile(),c.endpoint());
                if(c.credentialId()!=null && (!s.credentials.containsKey(c.credentialId()) || s.credentials.get(c.credentialId()).version()!=c.credentialVersion()))throw new IllegalArgumentException();
            }
            codes.clear();
            for(var e:s.models.entrySet()) {
                var m=e.getValue();if(m==null || !e.getKey().equals(m.id()) || !ModelResourceId.belongs(m.id(),'m',a) || m.version()<1 || !codes.add(m.code()) || !s.connections.containsKey(m.connectionId()) || m.options()==null)throw new IllegalArgumentException();
                code(m.code());text(m.name(),200);text(m.modelId(),256);
                if(m.memoryThreshold()<1 || m.maxContextTokens()<=m.memoryThreshold() || m.maxContextTokens()>100_000_000)throw new IllegalArgumentException();
                var definition=new MultiProviderProperties.Model();definition.setCapabilityProfile(m.capabilityProfile());definition.setMaxContextTokens(m.maxContextTokens());
                var options=new MultiProviderProperties.Options();options.setThinkingMode(m.options().thinkingMode());options.setThinkingBudget(m.options().thinkingBudget());options.setTemperature(m.options().temperature());options.setMaxOutputTokens(m.options().maxOutputTokens());options.setReasoningEffort(m.options().reasoningEffort());definition.setDefaultOptions(options);
                var source=s.connections.get(m.connectionId());var connection=new MultiProviderProperties.Connection();connection.setApiStyle(source.apiStyle());connection.setCompatibilityProfile(source.compatibilityProfile());connection.setEndpoint(source.endpoint());
                ProviderCompatibilityPolicy.validate(definition,connection);
                if(m.tokenLimits()!=null) {
                    var limits=m.tokenLimits();
                    if(limits.maxContextTokens()!=null && ModelTokenPolicy.context(m.modelId(),limits.maxContextTokens())!=m.maxContextTokens())throw new IllegalArgumentException();
                    if(!Objects.equals(m.options().maxOutputTokens(),ModelTokenPolicy.output(limits.maxOutputTokens(),m.maxContextTokens(),m.options().thinkingMode(),m.options().thinkingBudget(),ModelPurpose.MAIN_CHAT)))throw new IllegalArgumentException();
                }
                if(!Objects.equals(m.options().maxOutputTokens(),ProviderCompatibilityPolicy.effectiveOutputLimit(definition,connection))
                        || !Objects.equals(m.options().reasoningEffort(),ProviderCompatibilityPolicy.effectiveReasoningEffort(definition,connection)))throw new IllegalArgumentException();
            }
            for(var e:s.bindings.entrySet())if(e.getKey()==null || e.getValue()==null || e.getValue().version()<1 || !s.models.containsKey(e.getValue().modelConfigId())
                    || (e.getKey()==ModelPurpose.MAIN_CHAT && !s.models.get(e.getValue().modelConfigId()).chatVisible()))throw new IllegalArgumentException();
            for(var e:s.aliases.entrySet())if(e.getKey()==null || e.getKey().isBlank() || !s.models.containsKey(e.getValue()))throw new IllegalArgumentException();
        }catch(RuntimeException e){throw ModelManagementException.unavailable();}
    }
    private static void code(String value){if(value==null || !value.matches("[A-Za-z0-9._-]{1,128}"))throw new IllegalArgumentException();}
    private static void text(String value,int max){if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(c->c<32 || c==127))throw new IllegalArgumentException();}
}
