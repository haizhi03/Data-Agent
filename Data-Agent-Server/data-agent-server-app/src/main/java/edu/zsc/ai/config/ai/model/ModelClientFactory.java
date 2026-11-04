package edu.zsc.ai.config.ai.model;

public interface ModelClientFactory {
    boolean supports(ModelApiStyle apiStyle);
    ModelClientBundle create(ResolvedModelConfig config);
}
