package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.config.ai.model.*;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="ai.model-routing.persistence-enabled",havingValue="true")
public class FileCredentialStore {
    private final FileModelStore store;
    private final ModelCredentialCipher cipher;
    public String create(FileModelState state,ModelAccessContext scope,String secret,long version){
        if(secret==null || secret.isBlank() || secret.length()>16384 || secret.matches("[＊*]+") || secret.chars().anyMatch(c->c<32 || c==127))throw ModelManagementException.invalid();
        String id=ModelResourceId.create('k',scope),keyVersion=cipher.activeVersion();
        state.credentials.put(id,new FileModelState.Credential(id,"ENCRYPTED",cipher.encrypt(secret,scope,id,keyVersion),null,keyVersion,version));return id;
    }
    /** A detached connection owns an independent credential, so rotating it cannot revoke another model. */
    public String copy(FileModelState state,ModelAccessContext scope,String sourceId) {
        var source=state.credentials.get(sourceId);
        if(source==null)throw ModelManagementException.unavailable();
        if("ENCRYPTED".equals(source.kind()))return create(state,scope,cipher.decrypt(source.encryptedPayload(),scope,source.id(),source.keyVersion()),1);
        String id=ModelResourceId.create('k',scope);
        state.credentials.put(id,new FileModelState.Credential(id,source.kind(),null,source.secretRef(),null,1));return id;
    }
    public <T>T withSecret(ResolvedModelConfig config,Function<String,T> action,Function<String,String> deployment){
        String ref=config.credentialRef();if(ref==null || !ref.startsWith("credential:"))throw ModelIntegrationException.unavailable();
        String id=ref.substring(11);if(!ModelResourceId.belongs(id,'k',config.access()))throw ModelIntegrationException.unavailable();
        String secret;
        try{
            secret=store.read(config.access(),s->{
                var k=s.credentials.get(id);if(k==null || k.version()!=config.credentialVersion())throw ModelIntegrationException.unavailable();
                return "ENCRYPTED".equals(k.kind())?cipher.decrypt(k.encryptedPayload(),config.access(),id,k.keyVersion()):deployment.apply(k.secretRef());
            });
        }catch(RuntimeException e){throw ModelIntegrationException.unavailable();}
        if(secret==null || secret.isBlank() || secret.equals("******"))throw ModelIntegrationException.unavailable();
        return action.apply(secret); // Never hold the file lock while calling the consumer.
    }
}
