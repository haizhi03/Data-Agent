package edu.zsc.ai.local;

import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;

/** First write increment: one DML statement, literal values/expressions, no executable comments or functions. */
final class LocalWriteSql {
    private static final Set<String> FORBIDDEN=Set.of("SELECT","WITH","RETURNING","OUTPUT","INTO","OUTFILE","DUMPFILE",
            "CALL","EXEC","EXECUTE","CREATE","ALTER","DROP","TRUNCATE","COMMIT","ROLLBACK","GRANT","REVOKE",
            "LOCK","UNLOCK","USE","MERGE","REPLACE","NEXTVAL","CURRVAL","SETVAL","NEXT","FOR","ON","DUPLICATE","IGNORE","JOIN","USING");
    static void validate(String sql, String catalog, String schema) {
        if (sql==null || sql.isBlank() || sql.length()>32000) throw LocalRequestException.invalid();
        rejectComments(sql);
        try {
            var parsed=CCJSqlParserUtil.parseStatements(sql).getStatements();
            if (parsed.size()!=1) throw unsupported();
            var statement=parsed.getFirst();
            if (!(statement instanceof Insert || statement instanceof Update || statement instanceof Delete)) throw unsupported();
            if (statement instanceof Update update && (nonempty(update.getJoins()) || nonempty(update.getStartJoins()))) throw unsupported();
            if (statement instanceof Delete delete && (nonempty(delete.getJoins()) || nonempty(delete.getUsingList()) || (delete.getTables()!=null && delete.getTables().size()>1))) throw unsupported();
            var tokens=LocalReadOnlySql.tokens(sql);
            for (int i=0;i<tokens.size();i++) {
                String token=tokens.get(i);
                // INSERT INTO is the only permitted INTO. No stored/unknown functions or bound parameters in v1.
                if ((FORBIDDEN.contains(token) && !(token.equals("INTO") && i==1 && tokens.getFirst().equals("INSERT")))
                        || (token.equals("FROM") && !(i==1 && tokens.getFirst().equals("DELETE")))
                        || Set.of("@",":","?","[","]").contains(token)) throw unsupported();
                if (i+1<tokens.size() && tokens.get(i+1).equals("(")
                        && (token.equals("<QUOTED>") || Character.isUnicodeIdentifierStart(token.codePointAt(0)))) {
                    boolean insertColumns=statement instanceof Insert && i>=2 && Set.of("INTO",".").contains(tokens.get(i-1)) && tokens.subList(0,i).stream().noneMatch(t -> t.equals("("));
                    if (!insertColumns && ((i>0 && tokens.get(i-1).equals(".")) || !(Set.of("IN","AND","OR","NOT","WHERE").contains(token) || (token.equals("VALUES") && statement instanceof Insert && !tokens.subList(0,i).contains("VALUES"))))) throw unsupported();
                }
            }
            // Reject joins/multi-target DML. TablesNamesFinder also checks every table's namespace.
            if (new net.sf.jsqlparser.util.TablesNamesFinder().getTableList(statement).size()!=1) throw unsupported();
            LocalReadTools.validateNamespace(sql,catalog,schema);
        } catch (LocalRequestException e) { throw e; }
        catch (Exception e) { throw unsupported(); }
    }
    private static boolean nonempty(java.util.Collection<?> values) { return values!=null && !values.isEmpty(); }
    private static void rejectComments(String sql) {
        char quote=0;
        for (int i=0;i<sql.length();i++) {
            char c=sql.charAt(i);
            if (quote!=0) {
                if (c==quote) { if (i+1<sql.length() && sql.charAt(i+1)==quote) i++; else quote=0; }
                if (c=='\\') throw unsupported();
            } else if (c=='\'' || c=='"' || c=='`') quote=c;
            else if (c=='#' || (i+1<sql.length() && ((c=='-' && sql.charAt(i+1)=='-') || (c=='/' && sql.charAt(i+1)=='*')))) throw unsupported();
        }
    }
    static LocalRequestException unsupported() {
        return new LocalRequestException(409,"LOCAL_WRITE_SQL_UNSUPPORTED","写入仅支持单条受限 INSERT / UPDATE / DELETE，请检查 SQL");
    }
}
