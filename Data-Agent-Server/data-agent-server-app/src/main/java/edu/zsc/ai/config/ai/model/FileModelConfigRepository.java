package edu.zsc.ai.config.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.domain.service.ai.model.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component @Primary @RequiredArgsConstructor
@ConditionalOnProperty(name="ai.model-routing.persistence-enabled",havingValue="true")
public class FileModelConfigRepository implements ModelConfigRepository {
    private final FileModelStore store;
    private final MultiProviderProperties properties;
    public boolean enabled(){return properties.getModelRouting().isEnabled();}
    public int maxCallsPerRun(){return properties.getModelRouting().getMaxCallsPerRun();}
    public <T>T inSnapshot(ModelAccessContext a,java.util.function.Supplier<T> action){return store.snapshot(a,action);}
    public List<ResolvedModelConfig> list(ModelAccessContext a){return store.read(a,s->{
        var result=new ArrayList<>(s.models.keySet().stream().map(id->resolve(s,id,a)).filter(Objects::nonNull).toList());
        var connections=new HashSet<String>();
        for(var source:s.models.values()) {
            if(!presetSource(s,source) || !connections.add(source.connectionId()))continue;
            for(var specification:QwenModelSpecifications.all()) {
                if(result.stream().anyMatch(model->model.chatVisible() && model.connectionId().equals(source.connectionId())
                        && model.modelId().equals(specification.modelId())))continue;
                var preset=resolvePreset(s,new ChatPreset(source.id(),specification.modelId()),a,ModelPurpose.MAIN_CHAT);
                if(preset!=null)result.add(preset);
            }
        }
        return List.copyOf(result);
    });}
    public ResolvedModelConfig find(String id){return find(id,ModelPurpose.MAIN_CHAT);}
    public ResolvedModelConfig find(String id,ModelPurpose purpose){
        ChatPreset preset=ChatPreset.parse(id);
        String sourceId=preset==null?id:preset.sourceId();
        ModelAccessContext a;
        try {
            if(sourceId!=null && sourceId.startsWith("startup/") && properties.getModelRouting().isLegacyAliasesEnabled()) {
                var parts=sourceId.split("/",-1);if(parts.length!=4 || !parts[2].matches("[1-9][0-9]{0,18}") || !parts[3].matches("[A-Za-z0-9._-]+"))return null;
                a=new ModelAccessContext(0,WorkspaceTypeEnum.valueOf(parts[1]),Long.parseLong(parts[2]));
            }else{a=ModelResourceId.scope(sourceId);if(!ModelResourceId.belongs(sourceId,'m',a))return null;}
        }catch(RuntimeException e){return null;}
        var scope=a;
        try{return store.read(scope,s->preset!=null?resolvePreset(s,preset,scope,purpose)
                :resolve(s,id.startsWith("startup/")?s.aliases.get(id):id,scope,purpose));}
        catch(ModelManagementException e){if(e.status()==org.springframework.http.HttpStatus.NOT_FOUND)return null;throw e;}
    }
    public String binding(ModelAccessContext a,ModelPurpose purpose){return store.read(a,s->{var b=s.bindings.get(purpose);return b==null?null:b.modelConfigId();});}
    public String alias(ModelAccessContext a,String name){return properties.getModelRouting().isLegacyAliasesEnabled()?store.read(a,s->s.aliases.get(name)):null;}
    public boolean permitsSnapshot(ResolvedModelConfig snapshot){
        return store.read(snapshot.access(),s->{
            var preset=ChatPreset.parse(snapshot.modelConfigId());
            if(preset!=null) {
                var current=resolvePreset(s,preset,snapshot.access(),ModelPurpose.MAIN_CHAT);
                return current!=null && current.connectionId().equals(snapshot.connectionId())
                        && current.modelId().equals(snapshot.modelId()) && current.credentialRef().equals(snapshot.credentialRef())
                        && current.credentialVersion()==snapshot.credentialVersion();
            }
            var m=s.models.get(snapshot.modelConfigId());var c=s.connections.get(snapshot.connectionId());
            var k=c==null?null:s.credentials.get(c.credentialId());
            return m!=null && m.enabled() && c!=null && c.enabled() && k!=null
                    && snapshot.credentialRef().equals("credential:"+k.id()) && snapshot.credentialVersion()==k.version();
        });
    }
    /** Read-only choices share an enabled, chat-visible user configuration; no binding or JSON writes. */
    private static boolean presetSource(FileModelState s,FileModelState.Model source) {
        if(source==null || source.taskPreset() || !source.enabled() || !source.chatVisible())return false;
        var connection=s.connections.get(source.connectionId());
        return connection!=null && connection.enabled() && "qwen-chat-v1".equals(connection.compatibilityProfile())
                && s.credentials.containsKey(connection.credentialId());
    }
    private ResolvedModelConfig resolvePreset(FileModelState s,ChatPreset preset,ModelAccessContext a,ModelPurpose purpose) {
        var source=s.models.get(preset.sourceId());if(!presetSource(s,source))return null;
        var specification=QwenModelSpecifications.all().stream().filter(model->model.modelId().equals(preset.modelId())).findFirst().orElse(null);
        if(specification==null)return null;
        // Explicitly hidden/disabled models must not be reintroduced by the connection presets.
        if(s.models.values().stream().anyMatch(model->model.connectionId().equals(source.connectionId()) && model.modelId().equals(preset.modelId())
                && (!model.enabled() || !model.taskPreset() && !model.chatVisible())))return null;
        var base=resolve(s,source.id(),a,purpose);if(base==null)return null;
        var initial=new ResolvedModelConfig.EffectiveOptions(MultiProviderProperties.ThinkingMode.DISABLED,null,null,8192);
        var variant=new FileModelState.Model(preset.id(),"preset",source.connectionId(),source.name(),preset.modelId(),"qwen-tools-v1",initial,
                specification.contextTokens(),Math.min(24000,specification.contextTokens()-1),true,source.version(),true,new ConfiguredTokenLimits(null,null));
        var binding=s.bindings.get(purpose);
        var options=ModelThinkingOptions.options(variant,s.connections.get(source.connectionId()),binding==null?null:binding.thinkingMode(),purpose);
        return new ResolvedModelConfig(preset.id(),base.configVersion(),base.connectionId(),base.connectionVersion(),a,base.provider(),base.apiStyle(),
                preset.modelId(),base.name(),base.endpoint(),base.credentialRef(),base.credentialVersion(),"qwen-tools-v1",base.compatibilityProfile(),options,
                specification.contextTokens(),Math.min(24000,specification.contextTokens()-1),true,base.allowedPrivateHosts());
    }
    private record ChatPreset(String sourceId,String modelId) {
        String id(){return "qwen-preset/"+sourceId+"/"+modelId;}
        static ChatPreset parse(String id) {
            if(id==null || !id.startsWith("qwen-preset/"))return null;
            var parts=id.split("/",-1);
            return parts.length==3?new ChatPreset(parts[1],parts[2]):null;
        }
    }
    private ResolvedModelConfig resolve(FileModelState s,String id,ModelAccessContext a){
        return resolve(s,id,a,ModelPurpose.MAIN_CHAT);
    }
    private ResolvedModelConfig resolve(FileModelState s,String id,ModelAccessContext a,ModelPurpose purpose){
        var m=s.models.get(id);if(m==null || !m.enabled())return null;
        var c=s.connections.get(m.connectionId());if(c==null || !c.enabled())return null;
        var k=s.credentials.get(c.credentialId());if(k==null)return null;
        Set<String> hosts=new HashSet<>(c.allowedPrivateHosts());properties.getModelRouting().getAllowedPrivateHosts().forEach(host->hosts.add(host.toLowerCase(Locale.ROOT)));
        var binding=s.bindings.get(purpose);
        var options=ModelThinkingOptions.options(m,c,binding==null?null:binding.thinkingMode(),purpose);
        int context=ModelThinkingOptions.context(m,c);
        return new ResolvedModelConfig(m.id(),m.version(),c.id(),c.version(),a,c.provider(),c.apiStyle(),m.modelId(),m.name(),ModelEndpointPolicy.normalize(c.endpoint()),
                "credential:"+k.id(),k.version(),m.capabilityProfile(),c.compatibilityProfile(),options,context,Math.min(m.memoryThreshold(),context-1),m.chatVisible(),hosts);
    }
}
