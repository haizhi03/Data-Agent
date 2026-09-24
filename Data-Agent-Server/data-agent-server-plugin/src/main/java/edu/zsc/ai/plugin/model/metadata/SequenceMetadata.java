package edu.zsc.ai.plugin.model.metadata;

import java.math.BigDecimal;

/** Read-only sequence definition attributes; no NEXTVAL is queried. */
public record SequenceMetadata(String name, BigDecimal minValue, BigDecimal maxValue,
                               BigDecimal incrementBy, boolean cycle, Integer cacheSize) {
}
