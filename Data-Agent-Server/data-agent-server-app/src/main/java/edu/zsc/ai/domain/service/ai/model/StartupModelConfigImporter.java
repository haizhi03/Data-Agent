package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** One atomic import per explicitly granted workspace; restarts never overwrite UI changes. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="ai.model-routing.persistence-enabled",havingValue="true")
public class StartupModelConfigImporter implements ApplicationRunner {
    private final FileModelStore store;
    private final StartupModelConfigRepository startup;
    @Override public void run(ApplicationArguments arguments){
        if(!startup.enabled())return;
        Map<String,List<ResolvedModelConfig>> groups=new LinkedHashMap<>();
        for(var model:startup.exportModels())groups.computeIfAbsent(prefix(model.access()),ignored->new ArrayList<>()).add(model);
        groups.forEach((prefix,models)->{
            var a=models.get(0).access();
            store.update(a,s->{
                if(s.startupImported)return null;
                Map<String,String> connections=new HashMap<>(),ids=new HashMap<>();
                for(var m:models){
                    String connection=connections.computeIfAbsent(m.connectionId(),ignored->{
                        String code=code(m.connectionId());
                        var existing=s.connections.values().stream().filter(c->c.code().equals(code)).findFirst();if(existing.isPresent())return existing.get().id();
                        String keyId=ModelResourceId.create('k',a),id=ModelResourceId.create('c',a);
                        s.credentials.put(keyId,new FileModelState.Credential(keyId,"DEPLOYMENT_REF",null,m.credentialRef(),null,m.credentialVersion()));
                        s.connections.put(id,new FileModelState.Connection(id,code,code,m.provider(),m.apiStyle(),m.endpoint().toString(),m.compatibilityProfile(),keyId,m.credentialVersion(),m.connectionVersion(),true,m.allowedPrivateHosts()));return id;
                    });
                    String id=s.models.values().stream().filter(old->old.code().equals(code(m.modelConfigId()))).map(FileModelState.Model::id).findFirst().orElse(null);
                    if(id==null){id=ModelResourceId.create('m',a);s.models.put(id,new FileModelState.Model(id,code(m.modelConfigId()),connection,m.name(),m.modelId(),m.capabilityProfile(),m.options(),m.maxContextTokens(),m.memoryThreshold(),m.chatVisible(),m.configVersion(),true));}
                    ids.put(m.modelConfigId(),id);s.aliases.putIfAbsent(m.modelConfigId(),id);
                }
                startup.exportAliases().forEach((name,target)->{if(name.startsWith(prefix) && ids.containsKey(target))s.aliases.putIfAbsent(name.substring(prefix.length()),ids.get(target));});
                startup.exportBindings().forEach((name,target)->{if(name.startsWith(prefix) && ids.containsKey(target))s.bindings.putIfAbsent(ModelPurpose.valueOf(name.substring(prefix.length())),new FileModelState.Binding(ids.get(target),1));});
                s.startupImported=true;return null;
            });
        });
    }
    private static String prefix(ModelAccessContext a){return "startup/"+a.workspaceType()+"/"+a.ownerId()+"/";}
    private static String code(String id){return id.substring(id.lastIndexOf('/')+1);}
}
