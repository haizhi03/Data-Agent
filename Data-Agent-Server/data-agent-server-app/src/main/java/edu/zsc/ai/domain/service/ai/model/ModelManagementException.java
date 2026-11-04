package edu.zsc.ai.domain.service.ai.model;

import org.springframework.http.HttpStatus;

/** Only stable, non-sensitive codes cross the management boundary. */
public final class ModelManagementException extends RuntimeException {
    private final HttpStatus status;
    public ModelManagementException(String code, HttpStatus status) { super(code, null, false, false); this.status = status; }
    public HttpStatus status() { return status; }
    public static ModelManagementException invalid() { return new ModelManagementException("MODEL_CONFIG_INVALID", HttpStatus.BAD_REQUEST); }
    public static ModelManagementException missing() { return new ModelManagementException("MODEL_CONFIG_NOT_FOUND", HttpStatus.NOT_FOUND); }
    public static ModelManagementException conflict() { return new ModelManagementException("MODEL_CONFIG_CONFLICT", HttpStatus.CONFLICT); }
    public static ModelManagementException unavailable() { return new ModelManagementException("MODEL_MANAGEMENT_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE); }
}
