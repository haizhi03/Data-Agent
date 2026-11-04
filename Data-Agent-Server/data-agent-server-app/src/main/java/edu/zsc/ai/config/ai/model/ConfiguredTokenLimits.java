package edu.zsc.ai.config.ai.model;

/** Null is an intentional automatic setting, separate from the materialized runtime limits. */
public record ConfiguredTokenLimits(Integer maxContextTokens, Integer maxOutputTokens) {}
