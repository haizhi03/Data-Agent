package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class FileModelStoreTest {
    @TempDir Path temporary;
    MultiProviderProperties properties;
    FileModelStore files;
    ModelAccessContext scope=new ModelAccessContext(1,WorkspaceTypeEnum.PERSONAL,1);
    @BeforeEach void setup(){properties=new MultiProviderProperties();properties.getModelRouting().setStorageDirectory(temporary.toString());files=new FileModelStore(properties);}
    Path target(){return temporary.resolve("PERSONAL/1/models.json");}
    void initialize(){files.update(scope,s->{s.startupImported=true;return null;});}

    @Test void independentInstancesMergeConcurrentWritesWithoutLostUpdates() throws Exception {
        var second=new FileModelStore(properties);var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try{
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<12;i++){int index=i;futures.add(pool.submit(()->{start.await();(index%2==0?files:second).update(scope,s->{String id=ModelResourceId.create('c',scope);s.connections.put(id,new FileModelState.Connection(id,"c"+index,"Connection","FIXTURE",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/v1/","openai-responses-v1",null,0,1,true,Set.of()));return null;});return null;}));}
            start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
            assertEquals(12,files.read(scope,s->s.connections.size()).intValue());assertEquals(12L,files.read(scope,s->s.revision).longValue());
        }finally{pool.shutdownNow();}
    }
    @Test void separateJvmLockBlocksWriterUntilReleased() throws Exception {
        initialize();Path lock=temporary.resolve("PERSONAL/1/models.lock"),marker=temporary.resolve("holder-ready");
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),LockHolder.class.getName(),lock.toString(),marker.toString()).redirectErrorStream(true).start();
        var executor=Executors.newSingleThreadExecutor();
        try{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.exists(marker) && process.isAlive() && System.nanoTime()<end)Thread.sleep(10);
            assertTrue(Files.exists(marker),"Separate JVM did not acquire the fixture lock");
            var update=executor.submit(()->files.update(scope,s->{s.startupImported=false;return null;}));
            assertThrows(TimeoutException.class,()->update.get(100,TimeUnit.MILLISECONDS));
            process.getOutputStream().write(1);process.getOutputStream().flush();
            update.get(5,TimeUnit.SECONDS);assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            assertFalse(files.read(scope,s->s.startupImported).booleanValue());
        }finally{process.destroyForcibly();executor.shutdownNow();}
    }
    public static class LockHolder {
        public static void main(String[] arguments) throws Exception {
            try(var channel=FileChannel.open(Path.of(arguments[0]),StandardOpenOption.WRITE);var lock=channel.lock()){
                Files.writeString(Path.of(arguments[1]),"ready");System.in.read();
            }
        }
    }
    @Test void failedAtomicReplacePreservesOldFileAndOnlyCleansItsOwnTemporaryFile() throws Exception {
        initialize();byte[] before=Files.readAllBytes(target());Path legacy=target().resolveSibling("models.json.tmp");Files.writeString(legacy,"existing-file");
        var failing=new FileModelStore(properties){@Override protected void replace(Path temporary,Path target)throws IOException{throw new IOException("fixture replacement failure");}};
        assertThrows(ModelManagementException.class,()->failing.update(scope,s->{s.startupImported=false;return null;}));
        assertArrayEquals(before,Files.readAllBytes(target()));assertEquals("existing-file",Files.readString(legacy));
        try(var paths=Files.list(target().getParent())){assertTrue(paths.noneMatch(p->p.getFileName().toString().startsWith(".models-")));}
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(target()));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(target().getParent()));
    }
    @Test void corruptUnknownAndMismatchedFilesAreNeverSilentlyReset() throws Exception {
        initialize();String good=Files.readString(target());
        for(String bad:List.of("", "{broken",good.replace("\"formatVersion\":1","\"formatVersion\":2"),good.replace("\"ownerId\":1","\"ownerId\":2"),good.replace("\"formatVersion\":1","\"formatVersion\":1,\"formatVersion\":1"))){
            Files.writeString(target(),bad);
            assertThrows(ModelManagementException.class,()->files.read(scope,s->s.revision));
            assertThrows(ModelManagementException.class,()->files.update(scope,s->{s.startupImported=false;return null;}));
            assertEquals(bad,Files.readString(target()));
        }
    }
    @Test void rejectsSymlinksWithoutReadingOrOverwritingTheirTarget() throws Exception {
        initialize();Path outside=temporary.resolve("unrelated.json"),backup=temporary.resolve("backup");Files.writeString(outside,"unrelated");Files.move(target(),backup);Files.createSymbolicLink(target(),outside);
        assertThrows(ModelManagementException.class,()->files.read(scope,s->s.revision));assertThrows(ModelManagementException.class,()->files.update(scope,s->{s.startupImported=false;return null;}));
        assertEquals("unrelated",Files.readString(outside));
        Files.delete(target());Files.move(backup,target());Path lock=target().resolveSibling("models.lock");Files.delete(lock);Files.createSymbolicLink(lock,outside);
        assertThrows(ModelManagementException.class,()->files.read(scope,s->s.revision));assertEquals("unrelated",Files.readString(outside));
    }
    @Test void snapshotUsesOneRevisionAndReleasesTheLockAfterFailure() throws Exception {
        initialize();var second=new FileModelStore(properties);var executor=Executors.newSingleThreadExecutor();var pending=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try{
            files.snapshot(scope,()->{
                assertEquals(1L,files.read(scope,s->s.revision).longValue());
                pending.set(executor.submit(()->second.update(scope,s->{s.startupImported=false;return null;})));
                assertThrows(TimeoutException.class,()->pending.get().get(100,TimeUnit.MILLISECONDS));
                assertTrue(files.read(scope,s->s.startupImported).booleanValue());return null;
            });
            pending.get().get(5,TimeUnit.SECONDS);assertFalse(files.read(scope,s->s.startupImported).booleanValue());
            assertThrows(ModelManagementException.class,()->files.snapshot(scope,()->{throw ModelManagementException.invalid();}));
            assertDoesNotThrow(()->second.update(scope,s->{s.startupImported=true;return null;}));
        }finally{executor.shutdownNow();}
    }
    @Test void invalidCrossScopeRecordsAndFailedEditsDoNotCommit() throws Exception {
        for(String invalid:List.of("","/","relative-directory")){
            var settings=new MultiProviderProperties();settings.getModelRouting().setStorageDirectory(invalid);
            assertThrows(ModelManagementException.class,()->new FileModelStore(settings).read(scope,s->s.revision));
        }
        initialize();String before=Files.readString(target());var foreign=new ModelAccessContext(2,WorkspaceTypeEnum.PERSONAL,2);
        assertThrows(ModelManagementException.class,()->files.update(scope,s->{String id=ModelResourceId.create('c',foreign);s.connections.put(id,new FileModelState.Connection(id,"bad","Bad","FIXTURE",ModelApiStyle.OPENAI_RESPONSES,"https://example.com/v1","openai-responses-v1",null,0,1,true,Set.of()));return null;}));
        assertEquals(before,Files.readString(target()));
        assertThrows(ModelManagementException.class,()->files.update(scope,s->{s.startupImported=false;throw ModelManagementException.invalid();}));assertEquals(before,Files.readString(target()));
    }
}
