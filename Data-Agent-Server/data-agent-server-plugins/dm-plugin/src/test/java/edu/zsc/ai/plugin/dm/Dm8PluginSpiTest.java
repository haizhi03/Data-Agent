package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.Plugin;
import edu.zsc.ai.plugin.capability.SqlIdentifierEscaper;
import edu.zsc.ai.plugin.capability.SqlValidator;
import edu.zsc.ai.plugin.capability.SequenceManager;
import edu.zsc.ai.plugin.capability.ConstraintManager;
import edu.zsc.ai.plugin.driver.MavenCoordinates;
import edu.zsc.ai.plugin.enums.DbType;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.value.ValueProcessor;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline SPI and metadata tests for {@link Dm8Plugin}.
 */
class Dm8PluginSpiTest {

    private final Dm8Plugin plugin = new Dm8Plugin();

    @Test
    void serviceLoaderDiscoversDm8Plugin() {
        Dm8Plugin discovered = null;
        for (Plugin p : ServiceLoader.load(Plugin.class)) {
            if (p instanceof Dm8Plugin dm8) {
                discovered = dm8;
            }
        }
        assertNotNull(discovered, "Dm8Plugin must be discoverable via ServiceLoader");
        assertEquals("dm-8", discovered.getPluginId());
        assertEquals(DbType.DM, discovered.getDbType());
    }

    @Test
    void pluginMetadataComesFromAnnotation() {
        assertEquals("dm-8", plugin.getPluginId());
        assertEquals(DbType.DM, plugin.getDbType());
        assertEquals("DM 8", plugin.getDisplayName());
        assertEquals("8.1.0", plugin.getSupportMinVersion());
    }

    @Test
    void schemaSupportedButDatabaseNot() {
        assertFalse(plugin.supportDatabase());
        assertTrue(plugin.supportSchema());
    }

    @Test
    void exposesDmLanguageAndConversionCapabilities() {
        assertTrue(plugin instanceof SqlValidator);
        assertTrue(plugin instanceof SqlIdentifierEscaper);
        assertTrue(plugin instanceof ValueProcessor);
        assertTrue(plugin instanceof SequenceManager);
        assertTrue(plugin instanceof ConstraintManager);
        assertEquals("\"Mixed\"\"Case\"", plugin.quoteIdentifier("Mixed\"Case"));
        assertTrue(DefaultPluginManager.getInstance().supportsSqlValidationByPluginId("dm-8"));
        assertTrue(DefaultPluginManager.getInstance().getSqlAnalyzerByPluginId("dm-8") instanceof Dm8Plugin);
        assertTrue(DefaultPluginManager.getInstance().getSequenceManagerByPluginId("dm-8") instanceof Dm8Plugin);
        assertTrue(DefaultPluginManager.getInstance().getConstraintManagerByPluginId("dm-8") instanceof Dm8Plugin);
    }

    @Test
    void defaultDriverMavenCoordinates() {
        MavenCoordinates coordinates = plugin.getDriverMavenCoordinates(null);
        assertEquals("com.dameng", coordinates.getGroupId());
        assertEquals("DmJdbcDriver18", coordinates.getArtifactId());
        assertEquals("8.1.3.140", coordinates.getVersion());
        assertEquals("com.dameng:DmJdbcDriver18:8.1.3.140", coordinates.toCoordinateString());
    }

    @Test
    void emptyDriverVersionFallsBackToDefault() {
        MavenCoordinates coordinates = plugin.getDriverMavenCoordinates("");
        assertEquals("8.1.3.140", coordinates.getVersion());
    }

    @Test
    void explicitDriverVersionIsHonoured() {
        MavenCoordinates coordinates = plugin.getDriverMavenCoordinates("8.1.2.192");
        assertEquals("com.dameng", coordinates.getGroupId());
        assertEquals("DmJdbcDriver18", coordinates.getArtifactId());
        assertEquals("8.1.2.192", coordinates.getVersion());
    }

    @Test
    void jdbcUrlTemplateAndDefaultPort() {
        // protected members are accessible because this test lives in the same package
        assertEquals("jdbc:dm://%s:%d", plugin.getJdbcUrlTemplate());
        assertEquals(5236, plugin.getDefaultPort());
        assertEquals("dm.jdbc.driver.DmDriver", plugin.getDriverClassName());
        assertEquals("jdbc:dm://localhost:5236",
                String.format(plugin.getJdbcUrlTemplate(), "localhost", plugin.getDefaultPort()));
    }
}
