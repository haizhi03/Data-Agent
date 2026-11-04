package edu.zsc.ai.domain.service.ai.model;

import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.ModelAccessContext;
import edu.zsc.ai.config.ai.model.MultiProviderProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ModelCredentialCipherTest {
    @TempDir Path temporary;
    MultiProviderProperties properties() {
        var properties=new MultiProviderProperties(); properties.getModelRouting().setStorageDirectory(temporary.toString()); return properties;
    }
    @Test void aeadUsesFreshNoncesAndAuthenticatesWorkspaceIdAndKeyVersion() {
        Map<String,Object> keys=new HashMap<>(); keys.put("MODEL_CREDENTIAL_KEY_V1",Base64.getEncoder().encodeToString(new byte[32]));
        keys.put("MODEL_CREDENTIAL_KEY_V2",Base64.getEncoder().encodeToString(new byte[32]));
        var env=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return keys;}};
        var cipher=new ModelCredentialCipher(env,properties()); var a=new ModelAccessContext(1,WorkspaceTypeEnum.PERSONAL,1);
        String first=cipher.encrypt("fixture-secret",a,5,"V1"), second=cipher.encrypt("fixture-secret",a,5,"V1");
        assertNotEquals(first,second); assertFalse(first.contains("fixture-secret")); assertEquals("fixture-secret",cipher.decrypt(first,a,5,"V1"));
        assertThrows(ModelManagementException.class,()->cipher.decrypt(first,new ModelAccessContext(2,WorkspaceTypeEnum.PERSONAL,2),5,"V1"));
        assertThrows(ModelManagementException.class,()->cipher.decrypt(first,a,6,"V1"));
        assertThrows(ModelManagementException.class,()->cipher.decrypt(first,a,5,"V2"));
        byte[] tampered=Base64.getDecoder().decode(first); tampered[tampered.length-1]^=1;
        assertThrows(ModelManagementException.class,()->cipher.decrypt(Base64.getEncoder().encodeToString(tampered),a,5,"V1"));
        keys.remove("MODEL_CREDENTIAL_KEY_V1"); assertThrows(ModelManagementException.class,()->cipher.decrypt(first,a,5,"V1"));
    }
    @Test void refusesMissingWrongSizedAndInvalidMasterKeysWithoutExposingMaterial() {
        var env=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of("MODEL_CREDENTIAL_KEY_V1","fixture-invalid-base64");}};
        var cipher=new ModelCredentialCipher(env,properties()); assertFalse(cipher.configured());
        var e=assertThrows(ModelManagementException.class,()->cipher.encrypt("sensitive",new ModelAccessContext(1,WorkspaceTypeEnum.PERSONAL,1),1,"V1"));
        assertFalse(e.toString().contains("fixture-invalid")); assertNull(e.getCause());
    }
}
