package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import edu.zsc.ai.domain.exception.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class ModelHttpErrorTest {
    @Test void errorsBeforeSseUseTheDeclaredHttpStatusAndStableCode() {
        var response = new GlobalExceptionHandler().handleModelIntegrationException(
                new ModelIntegrationException("MODEL_PROVIDER_STATE_INCOMPATIBLE", HttpStatus.BAD_REQUEST));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("MODEL_PROVIDER_STATE_INCOMPATIBLE", response.getBody().getMessage());
        assertNull(response.getBody().getData());
    }
}
