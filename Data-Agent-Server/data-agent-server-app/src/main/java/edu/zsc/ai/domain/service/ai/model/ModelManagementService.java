package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.dto.request.ai.ModelManagementRequests;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ModelManagementService {
    private final FileModelStore store;
    private final ModelManagementAccess access;
    private final MultiProviderProperties properties;
    private final ModelCredentialCipher cipher;
    private final ObjectProvider<FileCredentialStore> credentials;
    private final ModelConfigRepository repository;

    public Status status() {
        access.current(false);
        return new Status(repository.enabled(),properties.getModelRouting().isPersistenceEnabled(),access.canManage(),cipher.configured());
    }
    public ModelAccessContext scope(boolean manage) {
        var a=access.current(manage);
        if(!repository.enabled() || !properties.getModelRouting().isPersistenceEnabled())throw ModelManagementException.unavailable();
        return a;
    }
    public List<Connection> connections() { return store.read(scope(true),s->s.connections.values().stream().map(c->connectionResponse(s,c)).toList()); }
    public Connection connection(String id) { return store.read(scope(true),s->connectionResponse(s,connection(s,id))); }
    public Connection createConnection(ModelManagementRequests.Connection input) {
        var a=scope(true);validate(input);
        return store.update(a,s->{
            uniqueConnection(s,input.code(),null);
            String credential=input.apiKey()==null?null:credentials.getObject().create(s,a,input.apiKey(),1);
            String id=ModelResourceId.create('c',a);
            var c=new FileModelState.Connection(id,input.code(),input.name().trim(),input.provider().trim(),input.apiStyle(),ModelEndpointPolicy.normalize(input.endpoint()).toString(),input.compatibilityProfile(),credential,credential==null?0:1,1,input.enabled(),Set.of());
            s.connections.put(id,c);return connectionResponse(s,c);
        });
    }
    public Connection updateConnection(String id,ModelManagementRequests.Connection input) {
        var a=scope(true);validate(input);if(input.apiKey()!=null)throw ModelManagementException.invalid();
        return store.update(a,s->{
            var old=connection(s,id);expected(input.expectedVersion(),old.version());uniqueConnection(s,input.code(),id);
            for(var m:s.models.values())if(m.connectionId().equals(id))validate(modelResponse(s,m),input.apiStyle(),input.compatibilityProfile(),input.endpoint());
            var c=new FileModelState.Connection(id,input.code(),input.name().trim(),input.provider().trim(),input.apiStyle(),ModelEndpointPolicy.normalize(input.endpoint()).toString(),input.compatibilityProfile(),old.credentialId(),old.credentialVersion(),old.version()+1,input.enabled(),old.allowedPrivateHosts());
            s.connections.put(id,c);return connectionResponse(s,c);
        });
    }
    public Connection credential(String id,ModelManagementRequests.Credential input) {
        var a=scope(true);
        return store.update(a,s->{
            var old=connection(s,id);expected(input.expectedVersion(),old.version());
            if(old.credentialVersion()==Long.MAX_VALUE)throw ModelManagementException.unavailable();
            String next;
            if("REPLACE".equals(input.action()))next=credentials.getObject().create(s,a,input.apiKey(),old.credentialVersion()+1);
            else if("UNLINK".equals(input.action()) && input.apiKey()==null)next=null;
            else throw ModelManagementException.invalid();
            var c=new FileModelState.Connection(id,old.code(),old.name(),old.provider(),old.apiStyle(),old.endpoint(),old.compatibilityProfile(),next,old.credentialVersion()+1,old.version()+1,old.enabled(),old.allowedPrivateHosts());
            s.connections.put(id,c);if(old.credentialId()!=null)s.credentials.remove(old.credentialId());
            return connectionResponse(s,c);
        });
    }
    public Connection disableConnection(String id,ModelManagementRequests.Version input) {
        return store.update(scope(true),s->{
            var old=connection(s,id);expected(input.expectedVersion(),old.version());
            var c=new FileModelState.Connection(id,old.code(),old.name(),old.provider(),old.apiStyle(),old.endpoint(),old.compatibilityProfile(),old.credentialId(),old.credentialVersion(),old.version()+1,false,old.allowedPrivateHosts());
            s.connections.put(id,c);return connectionResponse(s,c);
        });
    }
    /** The simplified UI saves connection, credential, model and defaults as one atomic document update. */
    public Setup saveSetup(ModelManagementRequests.Setup input) {
        var scope=scope(true);
        if(input==null)throw ModelManagementException.invalid();
        text(input.modelId(),256);
        String name=input.name()==null || input.name().isBlank()?input.modelId().trim():input.name().trim();
        validate(new ModelManagementRequests.Connection(null,"generated",name,input.provider(),input.apiStyle(),input.endpoint(),input.compatibilityProfile(),null,true));
        return store.update(scope,s->{
            var oldModel=input.modelConfigId()==null?null:model(s,input.modelConfigId());
            if(oldModel!=null) {
                expected(input.modelExpectedVersion(),oldModel.version());
                if(!oldModel.connectionId().equals(input.connectionId()))throw ModelManagementException.invalid();
            } else if(input.modelExpectedVersion()!=null)throw ModelManagementException.invalid();
            var old=input.connectionId()==null?null:connection(s,input.connectionId());
            if(old!=null)expected(input.connectionExpectedVersion(),old.version());
            else if(input.connectionExpectedVersion()!=null)throw ModelManagementException.invalid();

            boolean enabled=input.enabled()==null || input.enabled();
            boolean visible=input.chatVisible()==null || input.chatVisible();
            if(input.makeDefault() && (!enabled || !visible))throw ModelManagementException.invalid();
            if(oldModel!=null && !visible && s.bindings.containsKey(ModelPurpose.MAIN_CHAT)
                    && s.bindings.get(ModelPurpose.MAIN_CHAT).modelConfigId().equals(oldModel.id()))throw ModelManagementException.invalid();
            if(input.makeDefault())for(var purpose:ModelPurpose.values()) {
                Long expected=input.bindingVersions()==null?null:input.bindingVersions().get(purpose);version(expected,true);
                var binding=s.bindings.get(purpose);
                if(expected!=(binding==null?0:binding.version()))throw ModelManagementException.conflict();
            }

            String endpoint=ModelEndpointPolicy.normalize(input.endpoint()).toString();
            String secret=input.apiKey()==null || input.apiKey().isEmpty()?null:input.apiKey();
            boolean changed=old==null || secret!=null || !old.enabled() || !old.provider().equals(input.provider().trim())
                    || old.apiStyle()!=input.apiStyle() || !old.endpoint().equals(endpoint) || !old.compatibilityProfile().equals(input.compatibilityProfile());
            boolean shared=old!=null && s.models.values().stream().anyMatch(m->m.connectionId().equals(old.id()) && (oldModel==null || !m.id().equals(oldModel.id()))
                    && (!m.taskPreset() || !old.compatibilityProfile().equals(input.compatibilityProfile())));
            boolean detach=shared && changed;
            String connectionId=old==null || detach?ModelResourceId.create('c',scope):old.id();
            String credential=old==null?null:old.credentialId();
            long credentialVersion=old==null?0:old.credentialVersion();
            if(secret!=null) {
                if(credentialVersion==Long.MAX_VALUE)throw ModelManagementException.unavailable();
                credentialVersion=old==null || detach?1:credentialVersion+1;
                credential=credentials.getObject().create(s,scope,secret,credentialVersion);
            } else if(detach && credential!=null) {
                credential=credentials.getObject().copy(s,scope,credential);credentialVersion=1;
            }
            if(credential==null)throw new ModelManagementException("MODEL_TEST_CREDENTIAL_REQUIRED",org.springframework.http.HttpStatus.BAD_REQUEST);
            var c=shared && !detach?old:new FileModelState.Connection(connectionId,old==null || detach?"connection-"+UUID.randomUUID():old.code(),
                    name,input.provider().trim(),input.apiStyle(),endpoint,input.compatibilityProfile(),credential,credentialVersion,
                    old==null || detach?1:old.version()+1,true,old==null?Set.of():old.allowedPrivateHosts());
            s.connections.put(c.id(),c);

            String capability=input.capabilityProfile();
            if(capability==null || capability.isBlank())capability=ModelProfiles.all().stream()
                    .filter(p->p.apiStyle()==c.apiStyle() && p.compatibilityProfile().equals(c.compatibilityProfile())
                            && p.thinkingModes().contains(MultiProviderProperties.ThinkingMode.DISABLED))
                    .findFirst().orElseThrow(ModelManagementException::invalid).capabilityProfile();
            int context;
            var source=input.defaultOptions()==null?new MultiProviderProperties.Options():input.defaultOptions();
            var limits=new ConfiguredTokenLimits(input.maxContextTokens(),source.getMaxOutputTokens());
            var options=new MultiProviderProperties.Options();
            options.setThinkingMode(source.getThinkingMode());options.setThinkingBudget(source.getThinkingBudget());
            options.setTemperature(source.getTemperature());options.setReasoningEffort(source.getReasoningEffort());
            try {
                context=ModelTokenPolicy.context(input.modelId(),limits.maxContextTokens());
                options.setMaxOutputTokens(ModelTokenPolicy.output(input.modelId(),c.compatibilityProfile(),limits.maxOutputTokens(),context,source.getThinkingMode(),source.getThinkingBudget(),ModelPurpose.MAIN_CHAT));
            } catch(IllegalArgumentException e) { throw new ModelManagementException("MODEL_TOKEN_LIMITS_INVALID",org.springframework.http.HttpStatus.BAD_REQUEST); }
            int threshold=input.memoryThreshold()==null?Math.min(24000,context-1):input.memoryThreshold();
            var modelInput=new ModelManagementRequests.Model(null,oldModel==null?"model-"+UUID.randomUUID():oldModel.code(),c.id(),name,
                    input.modelId(),capability,options,context,threshold,visible,enabled);
            var effective=validate(modelInput,connectionResponse(s,c));
            String id=oldModel==null?ModelResourceId.create('m',scope):oldModel.id();
            if(oldModel!=null && oldModel.version()==Long.MAX_VALUE)throw ModelManagementException.unavailable();
            var m=modelData(id,oldModel==null?1:oldModel.version()+1,modelInput,effective).withTokenLimits(limits).withTaskPreset(oldModel!=null && oldModel.taskPreset());s.models.put(id,m);
            stopTaskConnectionIfUnused(s,m,old==null || detach || c.version()!=old.version());
            if(old!=null && old.credentialId()!=null && !old.credentialId().equals(credential)
                    && s.connections.values().stream().noneMatch(connection->old.credentialId().equals(connection.credentialId())))s.credentials.remove(old.credentialId());
            if(enabled)for(var purpose:ModelPurpose.values()) {
                if(purpose==ModelPurpose.MAIN_CHAT && !visible)continue;
                var previous=s.bindings.get(purpose);
                // Explicit defaults replace all purposes; otherwise only fill missing defaults.
                if(input.makeDefault() || previous==null) {
                    if(previous!=null && previous.version()==Long.MAX_VALUE)throw ModelManagementException.unavailable();
                    s.bindings.put(purpose,new FileModelState.Binding(id,previous==null?1:previous.version()+1));
                }
            }
            return new Setup(connectionResponse(s,connection(s,c.id())),modelResponse(s,m),Arrays.stream(ModelPurpose.values()).map(p->bindingResponse(s,p)).toList());
        });
    }
    public List<Model> models() { return store.read(scope(true),s->s.models.values().stream().map(m->modelResponse(s,m)).toList()); }
    public Model model(String id) { return store.read(scope(true),s->modelResponse(s,model(s,id))); }
    public Model createModel(ModelManagementRequests.Model input) {
        var a=scope(true);
        return store.update(a,s->{
            var c=connection(s,input.connectionId());var options=validate(input,connectionResponse(s,c));uniqueModel(s,input.code(),null);
            String id=ModelResourceId.create('m',a);var m=modelData(id,1,input,options);s.models.put(id,m);return modelResponse(s,m);
        });
    }
    public Model updateModel(String id,ModelManagementRequests.Model input) {
        return store.update(scope(true),s->{
            var old=model(s,id);expected(input.expectedVersion(),old.version());var c=connection(s,input.connectionId());
            var options=validate(input,connectionResponse(s,c));uniqueModel(s,input.code(),id);
            var main=s.bindings.get(ModelPurpose.MAIN_CHAT);
            if(!input.chatVisible() && main!=null && main.modelConfigId().equals(id))throw ModelManagementException.invalid();
            var m=modelData(id,old.version()+1,input,options).withTaskPreset(old.taskPreset());s.models.put(id,m);return modelResponse(s,m);
        });
    }
    public Model disableModel(String id,ModelManagementRequests.Version input) {
        return store.update(scope(true),s->{
            var m=model(s,id);expected(input.expectedVersion(),m.version());
            var disabled=new FileModelState.Model(id,m.code(),m.connectionId(),m.name(),m.modelId(),m.capabilityProfile(),m.options(),m.maxContextTokens(),m.memoryThreshold(),m.chatVisible(),m.version()+1,false);
            disabled=disabled.withTokenLimits(m.tokenLimits()).withTaskPreset(m.taskPreset());s.models.put(id,disabled);
            stopTaskConnectionIfUnused(s,disabled,false);
            return modelResponse(s,disabled);
        });
    }
    public List<Binding> bindings() {
        return store.read(scope(false),s->Arrays.stream(ModelPurpose.values()).map(p->bindingResponse(s,p)).toList());
    }
    public Binding bind(ModelPurpose purpose,ModelManagementRequests.Binding input) {
        return store.update(scope(true),s->{
            version(input.expectedVersion(),true);var old=s.bindings.get(purpose);
            if(input.expectedVersion()!=(old==null?0:old.version()))throw ModelManagementException.conflict();
            var m=model(s,input.modelConfigId());var c=s.connections.get(m.connectionId());
            if(!available(s,m,c) || (purpose==ModelPurpose.MAIN_CHAT && !m.chatVisible()))throw ModelManagementException.invalid();
            var thinking=bindingThinking(s,purpose,m,c,input.thinkingMode());
            long version=input.expectedVersion()+1;s.bindings.put(purpose,new FileModelState.Binding(m.id(),version,thinking));return bindingResponse(s,purpose);
        });
    }
    /** Resolve a provider preset and its purpose binding in one workspace-locked update, reusing the connection key. */
    public Binding bindPreset(ModelPurpose purpose,ModelManagementRequests.PresetBinding input) {
        var scope=scope(true);
        if(input==null || purpose==null)throw ModelManagementException.invalid();
        return store.update(scope,s->{
            version(input.expectedVersion(),true);var previous=s.bindings.get(purpose);
            if(input.expectedVersion()!=(previous==null?0:previous.version()))throw ModelManagementException.conflict();
            var c=connection(s,input.connectionId());expected(input.connectionExpectedVersion(),c.version());
            if(!c.enabled() || !s.credentials.containsKey(c.credentialId()) || !"qwen-chat-v1".equals(c.compatibilityProfile())
                    || s.models.values().stream().noneMatch(m->!m.taskPreset() && m.enabled() && m.connectionId().equals(c.id())))throw ModelManagementException.invalid();
            var spec=QwenModelSpecifications.all().stream().filter(candidate->candidate.modelId().equals(input.modelId())).findFirst().orElseThrow(ModelManagementException::invalid);
            var selected=s.models.values().stream().filter(m->m.connectionId().equals(c.id()) && m.modelId().equals(spec.modelId()) && m.enabled()
                    && (purpose!=ModelPurpose.MAIN_CHAT || m.chatVisible()))
                    .sorted(Comparator.comparing(FileModelState.Model::taskPreset)).findFirst().orElse(null);
            if(selected==null) {
                var reusable=s.models.values().stream().filter(m->m.taskPreset() && m.connectionId().equals(c.id()) && m.modelId().equals(spec.modelId())).findFirst().orElse(null);
                if(reusable!=null && reusable.version()==Long.MAX_VALUE)throw ModelManagementException.unavailable();
                String id=reusable==null?ModelResourceId.create('m',scope):reusable.id();
                var options=new MultiProviderProperties.Options();options.setMaxOutputTokens(ModelTokenPolicy.output(spec.modelId(),c.compatibilityProfile(),null,spec.contextTokens(),options.getThinkingMode(),null,ModelPurpose.MAIN_CHAT));
                var modelInput=new ModelManagementRequests.Model(null,reusable==null?"model-"+UUID.randomUUID():reusable.code(),c.id(),spec.modelId(),spec.modelId(),"qwen-tools-v1",options,
                        spec.contextTokens(),Math.min(24000,spec.contextTokens()-1),purpose==ModelPurpose.MAIN_CHAT || reusable!=null && reusable.chatVisible(),true);
                selected=modelData(id,reusable==null?1:reusable.version()+1,modelInput,validate(modelInput,connectionResponse(s,c)))
                        .withTokenLimits(new ConfiguredTokenLimits(null,null)).withTaskPreset(true);
                s.models.put(id,selected);
            }
            var thinking=bindingThinking(s,purpose,selected,c,input.thinkingMode());
            var binding=new FileModelState.Binding(selected.id(),input.expectedVersion()+1,thinking);s.bindings.put(purpose,binding);
            return bindingResponse(s,purpose);
        });
    }
    private MultiProviderProperties.ThinkingMode bindingThinking(FileModelState s,ModelPurpose purpose,FileModelState.Model model,
            FileModelState.Connection connection,MultiProviderProperties.ThinkingMode requested) {
        var previous=s.bindings.get(purpose);
        var mode=requested!=null?requested:previous==null?null:previous.thinkingMode();
        if(requested==null && mode!=null && !ModelThinkingOptions.modes(model,connection).contains(mode))mode=null;
        ModelThinkingOptions.validate(model,connection,mode,purpose);
        return mode;
    }
    private Binding bindingResponse(FileModelState s,ModelPurpose purpose) {
        var binding=s.bindings.get(purpose);var model=binding==null?null:s.models.get(binding.modelConfigId());
        var connection=model==null?null:s.connections.get(model.connectionId());
        var resolved=model==null?null:repository.find(model.id(),purpose);
        return new Binding(purpose,binding==null?null:binding.modelConfigId(),binding==null?0:binding.version(),
                resolved!=null?resolved.options().thinkingMode():model==null?null:model.options().thinkingMode(),ModelThinkingOptions.modes(model,connection));
    }
    public ResolvedModelConfig diagnosticModel(String id) {
        var a=scope(true);
        return repository.inSnapshot(a,()->{
            var c=repository.find(id);if(c==null || !StartupModelConfigRepository.sameScope(a,c.access()))throw ModelManagementException.missing();
            return new ResolvedModelConfig(c.modelConfigId(),c.configVersion(),c.connectionId(),c.connectionVersion(),a,c.provider(),c.apiStyle(),c.modelId(),c.name(),c.endpoint(),c.credentialRef(),c.credentialVersion(),c.capabilityProfile(),c.compatibilityProfile(),c.options(),c.maxContextTokens(),c.memoryThreshold(),c.chatVisible(),c.allowedPrivateHosts());
        });
    }
    private static FileModelState.Connection connection(FileModelState s,String id){var c=s.connections.get(id);if(c==null)throw ModelManagementException.missing();return c;}
    private static FileModelState.Model model(FileModelState s,String id){var m=s.models.get(id);if(m==null)throw ModelManagementException.missing();return m;}
    private static void stopTaskConnectionIfUnused(FileModelState s,FileModelState.Model m,boolean connectionAlreadyVersioned) {
        if(m.enabled() || m.taskPreset()
                || s.models.values().stream().noneMatch(other->other.taskPreset() && other.connectionId().equals(m.connectionId()))
                || s.models.values().stream().anyMatch(other->!other.taskPreset() && other.enabled() && other.connectionId().equals(m.connectionId())))return;
        var c=connection(s,m.connectionId());if(!c.enabled())return;
        if(!connectionAlreadyVersioned && c.version()==Long.MAX_VALUE)throw ModelManagementException.unavailable();
        s.connections.put(c.id(),new FileModelState.Connection(c.id(),c.code(),c.name(),c.provider(),c.apiStyle(),c.endpoint(),c.compatibilityProfile(),c.credentialId(),c.credentialVersion(),c.version()+(connectionAlreadyVersioned?0:1),false,c.allowedPrivateHosts()));
    }
    private static boolean available(FileModelState s,FileModelState.Model m,FileModelState.Connection c){return m.enabled() && c!=null && c.enabled() && s.credentials.containsKey(c.credentialId());}
    private static Connection connectionResponse(FileModelState s,FileModelState.Connection c){
        var key=s.credentials.get(c.credentialId());return new Connection(c.id(),c.code(),c.name(),c.provider(),c.apiStyle(),c.endpoint(),ModelEndpointPolicy.normalize(c.endpoint()).resolve(ModelProfiles.resource(c.apiStyle())).toString(),c.compatibilityProfile(),"API_KEY",key!=null,key==null?null:key.kind(),c.credentialVersion(),c.version(),c.enabled());
    }
    private static Model modelResponse(FileModelState s,FileModelState.Model m){
        var o=new MultiProviderProperties.Options();o.setThinkingMode(m.options().thinkingMode());o.setThinkingBudget(m.options().thinkingBudget());o.setTemperature(m.options().temperature());o.setMaxOutputTokens(m.options().maxOutputTokens());o.setReasoningEffort(m.options().reasoningEffort());
        if(m.tokenLimits()!=null)o.setMaxOutputTokens(m.tokenLimits().maxOutputTokens());
        int context=m.tokenLimits()!=null && m.tokenLimits().maxContextTokens()==null?ModelTokenPolicy.context(m.modelId(),null):m.maxContextTokens();
        var specification=QwenModelSpecifications.find(m.modelId(),s.connections.get(m.connectionId()).compatibilityProfile());
        if(specification!=null)context=Math.min(context,specification.contextTokens());
        return new Model(m.id(),m.code(),m.connectionId(),m.name(),m.modelId(),m.capabilityProfile(),o,context,Math.min(m.memoryThreshold(),context-1),m.chatVisible(),m.version(),m.enabled(),available(s,m,s.connections.get(m.connectionId())),m.tokenLimits(),m.taskPreset());
    }
    private static FileModelState.Model modelData(String id,long version,ModelManagementRequests.Model i,MultiProviderProperties.Options o){return new FileModelState.Model(id,i.code(),i.connectionId(),i.name().trim(),i.modelId().trim(),i.capabilityProfile(),new ResolvedModelConfig.EffectiveOptions(o.getThinkingMode(),o.getThinkingBudget(),o.getTemperature(),o.getMaxOutputTokens(),o.getReasoningEffort()),i.maxContextTokens(),i.memoryThreshold(),i.chatVisible(),version,i.enabled());}
    private static void uniqueConnection(FileModelState s,String code,String except){if(s.connections.values().stream().anyMatch(c->c.code().equals(code) && !c.id().equals(except)))throw ModelManagementException.conflict();}
    private static void uniqueModel(FileModelState s,String code,String except){if(s.models.values().stream().anyMatch(m->m.code().equals(code) && !m.id().equals(except)))throw ModelManagementException.conflict();}
    private static void validate(ModelManagementRequests.Connection c) {
        if(c==null) throw ModelManagementException.invalid(); code(c.code()); text(c.name(),200); text(c.provider(),64); text(c.endpoint(),2048);
        ModelProfiles.connection(c.apiStyle(),c.compatibilityProfile(),c.endpoint());
    }
    private static MultiProviderProperties.Options validate(ModelManagementRequests.Model input,Connection c) {
        code(input.code()); text(input.name(),200); text(input.modelId(),256);
        Model model=new Model(null,input.code(),input.connectionId(),input.name(),input.modelId(),input.capabilityProfile(),input.defaultOptions(),input.maxContextTokens(),input.memoryThreshold(),input.chatVisible(),1,input.enabled(),true);
        return validate(model,c.apiStyle(),c.compatibilityProfile(),c.endpoint());
    }
    private static MultiProviderProperties.Options validate(Model model,ModelApiStyle style,String compatibility,String endpoint) {
        if(model.defaultOptions()==null || model.memoryThreshold()<1 || model.maxContextTokens()<=model.memoryThreshold() || model.maxContextTokens()>100_000_000) throw ModelManagementException.invalid();
        var m=new MultiProviderProperties.Model(); m.setCapabilityProfile(model.capabilityProfile()); m.setDefaultOptions(model.defaultOptions()); m.setMaxContextTokens(model.maxContextTokens());
        var c=new MultiProviderProperties.Connection(); c.setApiStyle(style); c.setCompatibilityProfile(compatibility); c.setEndpoint(endpoint);
        try { ProviderCompatibilityPolicy.validate(m,c); } catch(RuntimeException e) { throw ModelManagementException.invalid(); }
        var specification=QwenModelSpecifications.find(model.modelId(),compatibility);
        if(specification!=null && (model.maxContextTokens()>specification.contextTokens()
                || (model.defaultOptions().getMaxOutputTokens()!=null && model.defaultOptions().getMaxOutputTokens()>specification.outputLimit(model.defaultOptions().getThinkingMode()))))
            throw new ModelManagementException("MODEL_TOKEN_LIMITS_INVALID",org.springframework.http.HttpStatus.BAD_REQUEST);
        var source=model.defaultOptions(); var result=new MultiProviderProperties.Options();
        result.setThinkingMode(source.getThinkingMode()); result.setThinkingBudget(source.getThinkingBudget()); result.setTemperature(source.getTemperature());
        result.setMaxOutputTokens(ProviderCompatibilityPolicy.effectiveOutputLimit(m,c)); result.setReasoningEffort(ProviderCompatibilityPolicy.effectiveReasoningEffort(m,c));
        return result;
    }
    private static void code(String code) { if(code==null || !code.matches("[A-Za-z0-9._-]{1,128}")) throw ModelManagementException.invalid(); }
    private static void text(String value,int max) { if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(c->c<32 || c==127)) throw ModelManagementException.invalid(); }
    public static void id(String value) { ModelResourceId.scope(value); }
    public static void version(Long value,boolean zero) { if(value==null || value<(zero?0:1) || value==Long.MAX_VALUE) throw ModelManagementException.invalid(); }
    private static void expected(Long value,long actual) { version(value,false); if(value!=actual)throw ModelManagementException.conflict(); }
}
