package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.dto.request.db.ExecuteSqlBatchRequest;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalWorkbenchPolicyTest {
    @Test void managedDriverCannotBeDeletedWhileReferencedByAConnection(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var repository = new LocalConnectionRepository(root.resolve("data"));
        var drivers = new LocalDriverService(repository);
        drivers.listInstalledDrivers("mysql");
        var file = java.nio.file.Files.write(root.resolve("data/drivers/mysql/mysql-connector-j-8.3.0.jar"), new byte[0]);
        edu.zsc.ai.context.RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
        try (var connections = LocalConnectionStoreTest.service(repository)) {
            var saved = connections.save(LocalConnectionStoreTest.input("driver-reference", file), true);
            LocalConnectionStoreTest.assertReason(() -> drivers.deleteDriver("mysql", "8.3.0"), "LOCAL_DRIVER_IN_USE");
            assertThat(file).exists();
            connections.delete(saved.id(), saved.version());
            drivers.deleteDriver("mysql", "8.3.0");
            assertThat(file).doesNotExist();
        } finally { edu.zsc.ai.context.RequestContext.clear(); }
    }
    @ParameterizedTest @ValueSource(strings = {"SELECT 1", "SELECT count(*) FROM demo", "SELECT 'DELETE; UPDATE' AS text",
            "WITH t AS (SELECT 1 AS n) SELECT n FROM t", "SELECT * FROM \"ERP_DEMO\".\"EMPLOYEES\" WHERE (1=1)",
            "SELECT 1; SELECT 2", "SELECT * FROM t WHERE x IN (1,2)", "SELECT COALESCE(x,0) FROM t", "SELECT COUNT(*) FROM 数据表"})
    void acceptsSupportedQueries(String sql) { assertThatCode(() -> LocalReadOnlySql.validate(sql)).doesNotThrowAnyException(); }
    @ParameterizedTest @ValueSource(strings = {"DELETE FROM t", "SELECT 1; DROP TABLE t", "WITH t AS (SELECT 1) DELETE FROM x",
            "SELECT * INTO new_table FROM t", "SELECT * FROM t FOR UPDATE", "SELECT 1 INTO OUTFILE '/tmp/out'",
            "SELECT seq.NEXTVAL FROM DUAL", "SELECT SLEEP(1)", "SELECT APP_MUTATE()", "SELECT app.count(*) FROM t",
            "SELECT 1 /*! INTO OUTFILE '/tmp/out' */", "SELECT @x := 1", "CALL run()", "BEGIN UPDATE t SET x=1; END;",
            "SELECT 改表()", "SELECT 𐀀()", "SELECT changé()", "SELECT \"SEQ\".\"NEXTVAL\" FROM DUAL"})
    void failsClosedForWritesSideEffectsAndUnsupportedSyntax(String sql) {
        assertThatThrownBy(() -> LocalReadOnlySql.validate(sql)).isInstanceOf(LocalRequestException.class);
    }
    @Test void rejectsEntireMixedScriptBeforeOpeningOrExecutingAnything() {
        var connections = mock(LocalConnectionService.class); var sql = mock(SqlExecutionServiceImpl.class);
        var controller = new LocalSqlController(connections, sql); var request = new ExecuteSqlBatchRequest();
        request.setConnectionId(1L); request.setSql("SELECT 1; DELETE FROM not_to_execute");
        assertThatThrownBy(() -> controller.script(request)).isInstanceOf(LocalRequestException.class);
        verifyNoInteractions(connections, sql);
    }
    @Test void bootstrapPipePayloadIsEncryptedAndBoundToTheParentKey() throws Exception {
        var identity = new LocalRuntimeIdentity(); byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        String frame = LocalHostBootstrap.frame(key, identity, 12345);
        assertThat(frame).doesNotContain(identity.transportTokenForHost());
        byte[] bytes = java.util.Base64.getDecoder().decode(frame);
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, java.util.Arrays.copyOf(bytes,12)));
        cipher.updateAAD("data-agent-local-bootstrap-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cipher.doFinal(java.util.Arrays.copyOfRange(bytes,12,bytes.length)));
        assertThat(payload.path("transportToken").asText()).isEqualTo(identity.transportTokenForHost());
        assertThat(payload.path("apiBaseURL").asText()).isEqualTo("http://127.0.0.1:12345/api");
    }
}
