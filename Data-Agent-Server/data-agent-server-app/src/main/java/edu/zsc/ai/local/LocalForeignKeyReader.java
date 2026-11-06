package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads declared outbound keys for one table, without model-generated catalog SQL. */
final class LocalForeignKeyReader {
    private final ConnectionService connections;

    LocalForeignKeyReader(ConnectionService connections) { this.connections=connections; }

    Map<String,Object> read(DbContext target,String table) {
        connections.openConnection(target);
        var active=ActiveConnectionRegistry.getOwnedConnection(target);
        try(var borrowed=active.borrowConnection();
            var rows=borrowed.connection().getMetaData().getImportedKeys(target.catalog(),target.schema(),table)) {
            var keys=new ArrayList<Map<String,Object>>();
            boolean truncated=false;
            while(rows.next()) {
                if(keys.size()==100) { truncated=true;break; }
                var key=new LinkedHashMap<String,Object>();
                key.put("name",rows.getString("FK_NAME"));
                key.put("column",rows.getString("FKCOLUMN_NAME"));
                key.put("referencedCatalog",rows.getString("PKTABLE_CAT"));
                key.put("referencedSchema",rows.getString("PKTABLE_SCHEM"));
                key.put("referencedTable",rows.getString("PKTABLE_NAME"));
                key.put("referencedColumn",rows.getString("PKCOLUMN_NAME"));
                key.put("keySequence",rows.getInt("KEY_SEQ"));
                keys.add(key);
            }
            return Map.of("available",true,"source","JDBC_IMPORTED_KEYS","keys",keys,"truncated",truncated);
        } catch(SQLException | UnsupportedOperationException e) {
            // Missing driver support is unknown metadata, never evidence of no keys.
            return Map.of("available",false,"source","JDBC_IMPORTED_KEYS","keys",java.util.List.of(),
                    "truncated",false,"errorCode","FOREIGN_KEY_METADATA_UNAVAILABLE");
        }
    }
}
