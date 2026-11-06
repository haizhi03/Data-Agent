package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalForeignKeyReaderTest {
    final DbContext target=new DbContext(7L,"sales",null);
    final ConnectionService connections=mock(ConnectionService.class);
    final ActiveConnectionRegistry.ActiveConnection active=mock(ActiveConnectionRegistry.ActiveConnection.class);
    final ActiveConnectionRegistry.BorrowedConnection borrowed=mock(ActiveConnectionRegistry.BorrowedConnection.class);
    final Connection jdbc=mock(Connection.class);
    final DatabaseMetaData metadata=mock(DatabaseMetaData.class);

    @Test void retainsCompositeKeyOrderAndReferenceIdentityAndClosesResources() throws Exception {
        var rows=mock(ResultSet.class);
        when(rows.next()).thenReturn(true,true,false);
        when(rows.getString("FK_NAME")).thenReturn("fk_user");
        when(rows.getString("FKCOLUMN_NAME")).thenReturn("tenant_id","user_id");
        when(rows.getString("PKTABLE_CAT")).thenReturn("identity");
        when(rows.getString("PKTABLE_NAME")).thenReturn("users");
        when(rows.getString("PKCOLUMN_NAME")).thenReturn("tenant_id","id");
        when(rows.getInt("KEY_SEQ")).thenReturn(1,2);
        when(metadata.getImportedKeys("sales",null,"orders")).thenReturn(rows);
        try(var registry=mockStatic(ActiveConnectionRegistry.class)) {
            prepare(registry);
            var result=new LocalForeignKeyReader(connections).read(target,"orders");
            assertThat(result).containsEntry("available",true).containsEntry("truncated",false);
            var keys=(List<?>)result.get("keys");assertThat(keys).hasSize(2);
            assertThat(keys.toString()).contains("keySequence=1","keySequence=2","referencedCatalog=identity","referencedTable=users");
            verify(metadata).getImportedKeys("sales",null,"orders");verify(rows).close();verify(borrowed).close();
        }
    }

    @Test void unavailableMetadataIsNotReportedAsConfirmedAbsenceOfKeys() throws Exception {
        when(metadata.getImportedKeys("sales",null,"orders")).thenThrow(new SQLFeatureNotSupportedException("driver-private-diagnostic"));
        try(var registry=mockStatic(ActiveConnectionRegistry.class)) {
            prepare(registry);
            var result=new LocalForeignKeyReader(connections).read(target,"orders");
            assertThat(result).containsEntry("available",false).containsEntry("errorCode","FOREIGN_KEY_METADATA_UNAVAILABLE");
            assertThat(result.toString()).doesNotContain("driver-private-diagnostic");verify(borrowed).close();
        }
    }

    @Test void largeForeignKeyListsCarryAnExplicitTruncationFlag() throws Exception {
        var rows=mock(ResultSet.class);when(rows.next()).thenReturn(true);
        when(metadata.getImportedKeys("sales",null,"orders")).thenReturn(rows);
        try(var registry=mockStatic(ActiveConnectionRegistry.class)) {
            prepare(registry);
            var result=new LocalForeignKeyReader(connections).read(target,"orders");
            assertThat((List<?>)result.get("keys")).hasSize(100);
            assertThat(result).containsEntry("available",true).containsEntry("truncated",true);
            verify(rows).close();verify(borrowed).close();
        }
    }

    private void prepare(org.mockito.MockedStatic<ActiveConnectionRegistry> registry) throws Exception {
        registry.when(()->ActiveConnectionRegistry.getOwnedConnection(target)).thenReturn(active);
        when(active.borrowConnection()).thenReturn(borrowed);
        when(borrowed.connection()).thenReturn(jdbc);
        when(jdbc.getMetaData()).thenReturn(metadata);
    }
}
