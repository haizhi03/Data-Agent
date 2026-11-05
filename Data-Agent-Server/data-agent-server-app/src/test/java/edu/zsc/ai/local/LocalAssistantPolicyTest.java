package edu.zsc.ai.local;

import dev.langchain4j.data.message.UserMessage;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.context.DbContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class LocalAssistantPolicyTest {
    @ParameterizedTest @EnumSource(LocalConversationRegistry.Engine.class)
    void acceptedRunPinsEngineEvenAfterCancellation(LocalConversationRegistry.Engine engine) {
        var registry=new LocalConversationRegistry();
        var session=registry.create(new DbContext(null,null,null),null);
        var first=new ModelRunSnapshot(Map.of(),1);
        registry.begin(session,first,engine);
        registry.cancel(session.id);
        registry.finish(session,first,null);
        var other=engine==LocalConversationRegistry.Engine.PI?LocalConversationRegistry.Engine.JAVA:LocalConversationRegistry.Engine.PI;
        assertThatThrownBy(() -> registry.begin(session,new ModelRunSnapshot(Map.of(),1),other))
                .isInstanceOfSatisfying(LocalRequestException.class,e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.response().reasonCode()).isEqualTo("LOCAL_SESSION_ENGINE_MISMATCH");
                });
        assertThat(session.active).isNull();
        var next=new ModelRunSnapshot(Map.of(),1);
        assertThatCode(() -> registry.begin(session,next,engine)).doesNotThrowAnyException();
        registry.shutdown();
    }

    @ParameterizedTest @ValueSource(strings={"SELECT * FROM other.t", "SELECT * FROM `other`.`t`", "SELECT * FROM \"other\".\"t\"", "SELECT * FROM db.other.t", "SELECT 1; SELECT 2"})
    void modelCannotEscapeThePinnedNamespaceOrRunMultipleStatements(String query) {
        assertThatThrownBy(() -> LocalReadTools.validateTarget(query,"db","allowed")).isInstanceOf(LocalRequestException.class);
    }
    @Test void qualifiedQueriesInTheExactNamespaceAreAllowed() {
        assertThatCode(() -> LocalReadTools.validateTarget("SELECT COUNT(*) FROM `allowed`.`t`","allowed",null)).doesNotThrowAnyException();
        assertThatCode(() -> LocalReadTools.validateTarget("SELECT * FROM \"allowed\".\"t\"",null,"allowed")).doesNotThrowAnyException();
    }
    @Test void activeSessionsAreNotEvictedAndCapacityIsExplicit() {
        var now=new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
        var clock=new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now.get();} };
        var registry=new LocalConversationRegistry(clock);
        var first=registry.create(new DbContext(null,null,null),null);
        var run=new ModelRunSnapshot(Map.of(),1); registry.begin(first,run);
        for(int i=1;i<32;i++)registry.create(new DbContext(null,null,null),null);
        assertThatThrownBy(() -> registry.create(new DbContext(null,null,null),null)).isInstanceOf(LocalRequestException.class);
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThat(registry.require(first.id)).isSameAs(first);
        assertThatCode(() -> registry.create(new DbContext(null,null,null),null)).doesNotThrowAnyException();
        registry.shutdown(); assertThatThrownBy(run::ensureActive).isInstanceOf(ModelIntegrationException.class);
    }
    @Test void cancelledOrLateRunCannotReplaceCommittedHistory() {
        var registry=new LocalConversationRegistry();var session=registry.create(new DbContext(null,null,null),null);
        var old=new ModelRunSnapshot(Map.of(),1);registry.begin(session,old);
        registry.cancel(session.id);registry.finish(session,old,null);
        var next=new ModelRunSnapshot(Map.of(),1);registry.begin(session,next);
        registry.finish(session,old,List.of(UserMessage.from("late content")));
        assertThat(session.messages).isEmpty();assertThat(session.active).isSameAs(next);
        registry.close(session.id);assertThatThrownBy(() -> registry.require(session.id)).isInstanceOf(LocalRequestException.class);
    }
    @Test void messageBudgetFailsWithoutDiscardingEarlierMessages() {
        var memory=new LocalChatMemory(List.of(UserMessage.from("original question")),100);
        assertThatThrownBy(() -> memory.add(UserMessage.from("x".repeat(101)))).isInstanceOf(ModelIntegrationException.class);
        assertThat(memory.messages()).containsExactly(UserMessage.from("original question"));
    }
}
