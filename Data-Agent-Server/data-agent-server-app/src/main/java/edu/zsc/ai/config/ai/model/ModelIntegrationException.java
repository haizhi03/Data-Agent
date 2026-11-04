package edu.zsc.ai.config.ai.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stable, safe messages only; never attach SDK exceptions containing requests or keys. */
public class ModelIntegrationException extends ResponseStatusException {
    public ModelIntegrationException(String code, HttpStatus status) { super(status, code); }
    public static ModelIntegrationException unavailable() {
        return new ModelIntegrationException("MODEL_CONFIG_UNAVAILABLE", HttpStatus.BAD_REQUEST);
    }
    public static ModelIntegrationException capability() {
        return new ModelIntegrationException("MODEL_CAPABILITY_UNSUPPORTED", HttpStatus.BAD_REQUEST);
    }
}
