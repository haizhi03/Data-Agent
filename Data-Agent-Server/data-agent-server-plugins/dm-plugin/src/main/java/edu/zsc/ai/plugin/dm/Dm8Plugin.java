package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.annotation.PluginInfo;
import edu.zsc.ai.plugin.enums.DbType;

@PluginInfo(
    id = "dm-8",
    name = "DM 8",
    version = "0.0.1",
    dbType = DbType.DM,
    description = "达梦 DM8 database plugin with JDBC, metadata and ANTLR SQL parsing support",
    supportMinVersion = "8.1.0"
)
public class Dm8Plugin extends DefaultDmPlugin {

    @Override
    protected String getDriverClassName() {
        return "dm.jdbc.driver.DmDriver";
    }
}
