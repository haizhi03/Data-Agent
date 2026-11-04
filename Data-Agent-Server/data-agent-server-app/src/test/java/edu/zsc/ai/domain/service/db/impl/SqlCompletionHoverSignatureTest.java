package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.*;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.plugin.capability.*;
import edu.zsc.ai.plugin.dm.Dm8Plugin;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.*;
import edu.zsc.ai.plugin.model.sql.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.sql.Connection;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlCompletionHoverSignatureTest {
    final DbContext db = new DbContext(1L, null, "APP");
    ConnectionService connections;
    ConnectionAccessService access;
    ColumnManager columns;
    FunctionManager functions;
    ProcedureManager procedures;
    TableManager tables;
    Dm8Plugin dm;
    Connection connection;
    SqlCompletionServiceImpl service;
    MockedStatic<DefaultPluginManager> managers;
    MockedStatic<ActiveConnectionRegistry> registry;
    @BeforeEach void setup() {
        connections = mock(ConnectionService.class); access = mock(ConnectionAccessService.class);
        DbConnectionService configs = mock(DbConnectionService.class);
        when(configs.getById(1L)).thenReturn(DbConnection.builder().id(1L).dbType("dm").name("本地DM").build());
        var manager = mock(DefaultPluginManager.class); dm = spy(new Dm8Plugin());
        doReturn(new CompletionFunctionCatalog(dm.functionTemplates(),null,false)).when(dm).functionCatalog(any());
        doReturn(List.of()).when(dm).completionPackages(any(),any());
        doReturn(List.of()).when(dm).completionSynonyms(any(),any());
        when(manager.getPluginByDbTypeAndVersion("dm", null)).thenReturn(dm);
        when(manager.getSqlCompleterByPluginId("dm-8")).thenReturn(dm);
        columns = mock(ColumnManager.class); tables = mock(TableManager.class);
        functions = mock(FunctionManager.class); procedures = mock(ProcedureManager.class);
        when(manager.getColumnManagerByPluginId("dm-8")).thenReturn(columns);
        when(manager.getTableManagerByPluginId("dm-8")).thenReturn(tables);
        when(manager.getViewManagerByPluginId("dm-8")).thenReturn(mock(ViewManager.class));
        when(manager.getFunctionManagerByPluginId("dm-8")).thenReturn(functions);
        when(manager.getProcedureManagerByPluginId("dm-8")).thenReturn(procedures);
        when(manager.getConstraintManagerByPluginId("dm-8")).thenReturn(mock(ConstraintManager.class));
        var active = mock(ActiveConnectionRegistry.ActiveConnection.class);
        var borrowed = mock(ActiveConnectionRegistry.BorrowedConnection.class);
        when(active.pluginId()).thenReturn("dm-8"); when(active.borrowConnection()).thenReturn(borrowed);
        connection = mock(Connection.class); when(borrowed.connection()).thenReturn(connection);
        when(tables.getTableNames(connection, null, "APP")).thenReturn(List.of("EMPLOYEE", "DEPARTMENT", "Mixed Table"));
        when(columns.getColumns(connection, null, "APP", "EMPLOYEE")).thenReturn(fields("ID", "NAME", "First Name", "MiX"));
        when(columns.getColumns(connection, null, "APP", "DEPARTMENT")).thenReturn(fields("ID", "TITLE"));
        when(columns.getColumns(connection, null, "APP", "Mixed Table")).thenReturn(fields("ID", "First Name"));
        managers = mockStatic(DefaultPluginManager.class); managers.when(DefaultPluginManager::getInstance).thenReturn(manager);
        registry = mockStatic(ActiveConnectionRegistry.class); registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(db)).thenReturn(active);
        service = new SqlCompletionServiceImpl(connections, configs, access, new SqlCompletionMetadataCache());
    }
    @AfterEach void close() { registry.close(); managers.close(); }
    static List<ColumnMetadata> fields(String... names) {
        return IntStream.range(0, names.length).mapToObj(i -> new ColumnMetadata(names[i], 12, "VARCHAR", 100, 0, true, i+1, "note " + names[i])).toList();
    }
    SqlHoverResponse hover(String marked) {
        return service.hover(db, marked.replace("|", ""), marked.indexOf('|'));
    }
    SqlSignatureHelpResponse signature(String marked) {
        return service.signature(db, marked.replace("|", ""), marked.indexOf('|'));
    }

    @Test void hoverTableShowsColumnsWithTypesAndComments() {
        String sql = "SELECT * FROM EMP|LOYEE";
        SqlHoverResponse result = hover(sql);
        assertEquals(sql.replace("|", "").indexOf("EMPLOYEE"), result.rangeStart());
        assertEquals(sql.replace("|", "").indexOf("EMPLOYEE") + 8, result.rangeEnd());
        assertTrue(result.markdown().startsWith("**数据源** `本地DM`"));
        assertTrue(result.markdown().contains("**架构** `APP`"));
        assertTrue(result.markdown().contains("**表** `EMPLOYEE`"));
        assertTrue(result.markdown().contains("| `ID` | `VARCHAR(100)` |"));
        assertTrue(result.markdown().contains("note NAME"));
    }
    @Test void hoverAliasResolvesBoundTable() {
        SqlHoverResponse result = hover("SELECT |e.ID FROM EMPLOYEE e");
        assertTrue(result.markdown().contains("**表** `EMPLOYEE`"));
        assertTrue(result.markdown().contains("别名 `E`"));
    }
    @Test void hoverQualifiedColumnShowsTypeAndComment() {
        SqlHoverResponse result = hover("SELECT e.N|AME FROM EMPLOYEE e");
        assertTrue(result.markdown().startsWith("**COLUMN** `APP.EMPLOYEE`.`NAME` : `VARCHAR(100)`"));
        assertTrue(result.markdown().contains("note NAME"));
    }
    @Test void hoverSchemaQualifiedTableUsesExplicitSchema() {
        SqlHoverResponse result = hover("SELECT * FROM APP.DEP|ARTMENT");
        assertTrue(result.markdown().contains("**架构** `APP`"));
        assertTrue(result.markdown().contains("**表** `DEPARTMENT`"));
        assertTrue(result.markdown().contains("`TITLE`"));
    }
    @Test void hoverCteShowsOutputColumns() {
        SqlHoverResponse result = hover("WITH q AS (SELECT ID FROM EMPLOYEE) SELECT * FROM |q");
        assertTrue(result.markdown().startsWith("**CTE** `Q`"));
        assertTrue(result.markdown().contains("`ID`"));
    }
    @Test void hoverVariableShowsDeclaredType() {
        SqlHoverResponse result = hover("DECLARE v_count NUMBER; BEGIN SELECT v_|count FROM EMPLOYEE; END;");
        assertEquals("**VARIABLE** `V_COUNT` : `NUMBER`", result.markdown());
    }
    @Test void hoverTableShowsCommentAndDdlWhenAvailable() {
        when(tables.getTableComment(connection, null, "APP", "EMPLOYEE")).thenReturn("员工记录表");
        when(tables.getTableDdl(connection, null, "APP", "EMPLOYEE"))
                .thenReturn("CREATE TABLE APP.EMPLOYEE (ID INT, NAME VARCHAR(100));");
        SqlHoverResponse result = hover("SELECT * FROM EMP|LOYEE");
        assertTrue(result.markdown().contains("员工记录表"));
        assertTrue(result.markdown().contains("```sql\nCREATE TABLE APP.EMPLOYEE"));
        assertFalse(result.markdown().contains("| Column |"));
    }
    @Test void hoverBuiltinFunctionShowsSignature() {
        SqlHoverResponse result = hover("SELECT NV|L(ID, 0) FROM EMPLOYEE");
        assertTrue(result.markdown().contains("NVL(value:ANY, replacement:ANY)"));
    }
    @Test void hoverStringCommentAndUnknownStayEmpty() {
        assertNull(hover("SELECT 'x|y' FROM EMPLOYEE").markdown());
        assertNull(hover("SELECT ID -- com|ment\nFROM EMPLOYEE").markdown());
        assertNull(hover("SELECT * FROM NO|THING").markdown());
    }
    @Test void hoverQuotedMixedCaseTableKeepsSpelling() {
        SqlHoverResponse result = hover("SELECT * FROM \"Mixed |Table\"");
        assertTrue(result.markdown().contains("**表** `Mixed Table`"));
        assertTrue(result.markdown().contains("`First Name`"));
    }

    @Test void builtinSignatureTracksCommaPosition() {
        SqlSignatureHelpResponse first = signature("SELECT NVL(|ID, 0) FROM EMPLOYEE");
        assertEquals(1, first.signatures().size());
        assertEquals("NVL(value:ANY, replacement:ANY) → ANY", first.signatures().get(0).label());
        assertEquals(List.of("value:ANY", "replacement:ANY"),
                first.signatures().get(0).parameters().stream().map(SqlSignatureParameter::label).toList());
        assertEquals(0, first.activeParameter());
        assertEquals(1, signature("SELECT NVL(ID, |0) FROM EMPLOYEE").activeParameter());
    }
    @Test void innermostCallWinsAndClosedCallsStayEmpty() {
        SqlSignatureHelpResponse nested = signature("SELECT NVL(UPPER(|NAME), 'x') FROM EMPLOYEE");
        assertEquals("UPPER(text:VARCHAR/CLOB) → VARCHAR/CLOB", nested.signatures().get(0).label());
        assertEquals(0, nested.activeParameter());
        assertTrue(signature("SELECT NVL(ID, 0)| FROM EMPLOYEE").signatures().isEmpty());
    }
    @Test void signatureInsideStringStaysEmpty() {
        assertTrue(signature("SELECT NVL('a|b', 0) FROM EMPLOYEE").signatures().isEmpty());
    }
    @Test void variadicParameterClampsToLastSlot() {
        SqlSignatureHelpResponse result = signature("SELECT CONCAT('a', 'b', 'c', |'d') FROM EMPLOYEE");
        assertEquals(3, result.signatures().get(0).parameters().size());
        assertEquals(2, result.activeParameter());
    }
    @Test void customFunctionUsesMetadataParameters() {
        when(functions.getFunctions(connection, null, "APP")).thenReturn(List.of(
                new FunctionMetadata("MYFUNC", List.of(new ParameterInfo("P1", "NUMBER"), new ParameterInfo("P2", "VARCHAR")), "NUMBER")));
        SqlSignatureHelpResponse result = signature("SELECT MYFUNC(1, |'x') FROM EMPLOYEE");
        assertEquals("MYFUNC(P1 NUMBER, P2 VARCHAR) → NUMBER", result.signatures().get(0).label());
        assertEquals(1, result.activeParameter());
    }
    @Test void packageOverloadsSelectByArgumentCount() {
        doReturn(List.of("UTILS")).when(dm).completionPackages(any(), any());
        doReturn(List.of(
                new CompletionRoutine("F", List.of(new ParameterInfo("A", "NUMBER")), "NUMBER", false, "1"),
                new CompletionRoutine("F", List.of(new ParameterInfo("A", "NUMBER"), new ParameterInfo("B", "NUMBER")), "NUMBER", false, "2")))
                .when(dm).completionPackageMembers(any(), eq("APP"), eq("UTILS"));
        SqlSignatureHelpResponse result = signature("BEGIN UTILS.F(1, |2); END;");
        assertEquals(2, result.signatures().size());
        assertEquals(1, result.activeSignature());
        assertEquals("F(A NUMBER, B NUMBER) → NUMBER", result.signatures().get(1).label());
        assertEquals(1, result.activeParameter());
    }
    @Test void unknownFunctionStaysEmpty() {
        assertTrue(signature("SELECT WHATEVER(|1) FROM EMPLOYEE").signatures().isEmpty());
        assertTrue(signature("SELECT * FROM EMPLOYEE WHERE ID IN (1, |2)").signatures().isEmpty());
    }
    @Test void offlineStillServesBuiltinSignatures() {
        registry.close();
        registry = mockStatic(ActiveConnectionRegistry.class);
        registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(db))
                .thenThrow(new IllegalStateException("no connection"));
        SqlSignatureHelpResponse result = signature("SELECT SUBSTR(NAME, |1) FROM EMPLOYEE");
        assertEquals("SUBSTR(text:VARCHAR/CLOB, start:INTEGER, [length:INTEGER]) → VARCHAR/CLOB",
                result.signatures().get(0).label());
        assertEquals(1, result.activeParameter());
    }
}
