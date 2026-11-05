package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The live evaluator must distinguish a group key / total from the requested disabled-user count. */
class LocalPiAggregateAssertionTest {
    final ObjectMapper json=new ObjectMapper();

    long count(String headers,String rows) throws Exception {
        return LocalPiMySqlLiveTest.disabledCount(json.readTree("{\"result\":{\"headers\":"+headers+",\"rows\":"+rows+"}}"));
    }
    @Test void acceptsScalarGroupedAndNamedConditionalCounts() throws Exception {
        assertThat(count("[\"count\"]","[[1]]")).isEqualTo(1);
        assertThat(count("[\"status\",\"cnt\"]","[[0,1],[1,11]]")).isEqualTo(1);
        assertThat(count("[\"cnt\",\"status\"]","[[1,0],[11,1]]")).isEqualTo(1);
        assertThat(count("[\"total_users\",\"disabled_users\"]","[[12,1]]")).isEqualTo(1);
    }
    @Test void doesNotMistakeTheTotalForTheRequestedCountOrGuessUnknownShapes() throws Exception {
        assertThat(count("[\"count\"]","[[12]]")).isNotEqualTo(1);
        assertThatThrownBy(() -> count("[\"status\",\"cnt\"]","[[1,12]]")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> count("[\"a\",\"b\"]","[[12,1]]")).isInstanceOf(AssertionError.class);
    }
}
