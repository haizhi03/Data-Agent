package edu.zsc.ai.plugin.dm.value.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DmValueProcessorFactoryTest {

    @Test
    void routesTimestampPrecisionWithTimeZoneToTimeZoneProcessor() {
        assertInstanceOf(DmTimestampWithTimeZoneProcessor.class,
                DmValueProcessorFactory.getValueProcessor("TIMESTAMP(6) WITH TIME ZONE"));
    }

    @Test
    void routesDateToDateOnlyProcessor() {
        assertInstanceOf(DmDateTimeProcessor.class,
                DmValueProcessorFactory.getValueProcessor("DATE"));
    }

    @Test
    void routesDatetimeToTimestampProcessor() {
        // DM DATETIME carries a time component; it must use the TIMESTAMP path,
        // not the date-only DATE path.
        assertInstanceOf(DmTimestampProcessor.class,
                DmValueProcessorFactory.getValueProcessor("DATETIME"));
    }

    @Test
    void routesTimestampPrecisionToTimestampProcessor() {
        assertInstanceOf(DmTimestampProcessor.class,
                DmValueProcessorFactory.getValueProcessor("TIMESTAMP(3)"));
    }
}
