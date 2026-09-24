package edu.zsc.ai.plugin.dm.sql;

import edu.zsc.ai.plugin.capability.SqlSplitter;
import edu.zsc.ai.plugin.dm.parser.DmSqlParser;

import java.util.List;

public final class DmSqlSplitter implements SqlSplitter {

    public static final DmSqlSplitter INSTANCE = new DmSqlSplitter();

    private DmSqlSplitter() {
    }

    @Override
    public List<String> split(String sql) {
        return DmSqlParser.INSTANCE.split(sql);
    }
}
