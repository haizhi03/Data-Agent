package edu.zsc.ai.config.ai.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static edu.zsc.ai.config.ai.model.MultiProviderProperties.ThinkingMode.*;

class ModelTokenPolicyTest {
    @Test void infersRecognizedNamesAndExplicitWindowSuffixesWithoutGuessingUnknownModels() {
        assertEquals(128000,ModelTokenPolicy.context("gpt-4o-2024-08-06",null));
        assertEquals(131072,ModelTokenPolicy.context("local-model-128k",null));
        assertEquals(1048576,ModelTokenPolicy.context("local-model-1m",null));
        assertEquals(1_000_000,ModelTokenPolicy.context("qwen3.8-max",null));
        assertEquals(64000,ModelTokenPolicy.context("gpt-4o",64000));
        assertThrows(IllegalArgumentException.class,()->ModelTokenPolicy.context("any",0));
    }
    @Test void usesTheFourOfficialSpecificationsAndOnlyVerifiedSnapshotAliases() {
        int[][] limits={{1_000_000,131072,131072},{1_000_000,65536,65536},{1_000_000,32768,32768},{1_000_000,65536,65536}};
        var models=QwenModelSpecifications.all();assertEquals(4,models.size());
        for(int i=0;i<models.size();i++) {
            var spec=models.get(i);assertEquals(limits[i][0],ModelTokenPolicy.context(spec.modelId(),null));
            assertEquals(limits[i][1],spec.outputLimit(DISABLED));assertEquals(limits[i][2],spec.outputLimit(ENABLED));
            for(var alias:spec.aliases())assertEquals(spec,QwenModelSpecifications.find(alias));
            assertTrue(spec.sourceUrl().startsWith("https://help.aliyun.com/"));
        }
        assertNull(QwenModelSpecifications.find("qwen3.8-plus"));
        assertNull(QwenModelSpecifications.find("qwen-plus-2025-01-25"));
        assertEquals("qwen3.6-flash",models.get(1).modelId());
        assertTrue(models.stream().noneMatch(s->s.modelId().equals("qwen3-max")));
        assertEquals(262144,QwenModelSpecifications.find("qwen3-max").contextTokens());
    }
    @Test void enforcesTheModelAndThinkingModeOutputCeiling() {
        assertEquals(65536,ModelTokenPolicy.output("qwen3-max","qwen-chat-v1",65536,262144,DISABLED,null,ModelPurpose.MAIN_CHAT));
        assertEquals(32768,ModelTokenPolicy.output("qwen3-max","qwen-chat-v1",32768,262144,ENABLED,null,ModelPurpose.MAIN_CHAT));
        assertThrows(IllegalArgumentException.class,()->ModelTokenPolicy.output("qwen3-max","qwen-chat-v1",65536,262144,ENABLED,null,ModelPurpose.MAIN_CHAT));
        assertThrows(IllegalArgumentException.class,()->ModelTokenPolicy.output("qwen-plus","qwen-chat-v1",32769,1_000_000,DISABLED,null,ModelPurpose.MAIN_CHAT));
        assertThrows(IllegalArgumentException.class,()->ModelTokenPolicy.output("qwen3-max","qwen-chat-v1",null,1_000_000,DISABLED,null,ModelPurpose.MAIN_CHAT));
        assertEquals(8192,ModelTokenPolicy.output("qwen3.8-max","qwen-chat-v1",null,1_000_000,DISABLED,null,ModelPurpose.MAIN_CHAT));
        assertEquals(2048,ModelTokenPolicy.output("qwen3.8-max","qwen-chat-v1",null,1_000_000,DISABLED,null,ModelPurpose.COMPRESSION));
        for(var thinking:new MultiProviderProperties.ThinkingMode[]{DISABLED,ENABLED}) {
            assertEquals(65536,ModelTokenPolicy.output("qwen3.6-flash","qwen-chat-v1",65536,1_000_000,thinking,null,ModelPurpose.MAIN_CHAT));
            assertThrows(IllegalArgumentException.class,()->ModelTokenPolicy.output("qwen3.6-flash","qwen-chat-v1",65537,1_000_000,thinking,null,ModelPurpose.MAIN_CHAT));
        }
    }
    @Test void automaticOutputUsesTheOperationAndFitsASmallContext() {
        assertEquals(8192,output(null,32768,DISABLED,ModelPurpose.MAIN_CHAT));
        assertEquals(8192,output(null,32768,DISABLED,ModelPurpose.EXPLORER));
        assertEquals(2048,output(null,32768,DISABLED,ModelPurpose.COMPRESSION));
        assertEquals(2048,output(null,32768,DISABLED,ModelPurpose.MEMORY_WRITER));
        assertEquals(511,output(null,512,DISABLED,ModelPurpose.MAIN_CHAT));
    }
    @Test void configuredOutputOverridesEveryOperationAndHasTheAdvertisedRange() {
        for(var purpose:ModelPurpose.values())assertEquals(10000,output(10000,32768,DISABLED,purpose));
        assertEquals(256,output(256,32768,DISABLED,ModelPurpose.MAIN_CHAT));
        assertEquals(1_000_000,output(1_000_000,1_048_576,DISABLED,ModelPurpose.MAIN_CHAT));
        for(int invalid:new int[]{0,255,1_000_001})assertThrows(IllegalArgumentException.class,()->output(invalid,2_000_000,DISABLED,ModelPurpose.MAIN_CHAT));
        assertThrows(IllegalArgumentException.class,()->output(32768,32768,DISABLED,ModelPurpose.MAIN_CHAT));
    }
    @Test void thinkingHasAMinimumAndAutomaticBudgetsReserveRoomForTheAnswer() {
        for(var purpose:ModelPurpose.values()) {
            assertEquals(8192,output(null,32768,ENABLED,purpose));
            assertEquals(8192,output(256,32768,ENABLED,purpose));
            assertEquals(8192,output(null,32768,ADAPTIVE,purpose));
        }
        assertEquals(12001,ModelTokenPolicy.output(null,32768,ENABLED,12000,ModelPurpose.COMPRESSION));
        assertThrows(IllegalArgumentException.class,()->output(null,8192,ENABLED,ModelPurpose.MAIN_CHAT));
    }
    private int output(Integer limit,int context,MultiProviderProperties.ThinkingMode mode,ModelPurpose purpose) {
        return ModelTokenPolicy.output(limit,context,mode,null,purpose);
    }
}
