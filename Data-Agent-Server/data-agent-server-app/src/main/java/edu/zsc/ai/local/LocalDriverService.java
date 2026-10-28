package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.dto.response.db.InstalledDriverResponse;
import edu.zsc.ai.domain.service.db.impl.DriverServiceImpl;
import edu.zsc.ai.plugin.driver.*;
import edu.zsc.ai.plugin.enums.DbType;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.util.DriverFileUtil;
import java.nio.file.*;
import java.util.*;

/** Uses existing Maven metadata/downloader, but confines managed files to this local data root. */
final class LocalDriverService extends DriverServiceImpl {
    private final LocalConnectionRepository repository;
    LocalDriverService(LocalConnectionRepository repository) { this.repository = repository; }
    private DbType type(String name) {
        try { return DbType.fromCode(name); } catch (RuntimeException e) { throw LocalRequestException.invalid(); }
    }
    private Path directory(DbType type) throws java.io.IOException {
        var root = repository.files.root.resolve("drivers"); repository.files.directory(repository.files.root);
        repository.files.directory(root); Path path = root.resolve(type.getCode()); repository.files.directory(path); return path;
    }
    @Override public List<InstalledDriverResponse> listInstalledDrivers(String databaseType) {
        DbType dbType = type(databaseType);
        try {
            Path directory = directory(dbType);
            try (var files = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path path : files) LocalPrivateFiles.rejectLink(path);
            }
            return DriverFileUtil.scanDriverDirectory(directory, dbType.getCode());
        } catch (java.io.IOException | UnsupportedOperationException e) { throw LocalRequestException.storeUnavailable(); }
    }
    @Override public List<edu.zsc.ai.domain.model.dto.response.db.AvailableDriverResponse> listAvailableDrivers(String name) {
        return super.listAvailableDrivers(type(name).getCode());
    }
    @Override public Path downloadDriver(String databaseType, String version) {
        DbType dbType = type(databaseType);
        if (version != null && !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw LocalRequestException.invalid();
        var coordinates = DefaultPluginManager.getInstance().getMavenCoordinatesByDbTypeAndVersion(dbType, version);
        try {
            Path directory = directory(dbType), target = directory.resolve(coordinates.getArtifactId() + "-" + coordinates.getVersion() + ".jar");
            LocalPrivateFiles.rejectLink(target);
            if (Files.exists(target)) { JarFileValidator.validate(target); return target; }
            Path staging = Files.createTempDirectory(repository.files.root.resolve("drivers"), ".download-",
                    repository.files.creationAttributes(directory, true));
            try {
                Path source = MavenDriverDownloader.downloadDriver(coordinates, dbType, staging.toString(), null);
                byte[] data = repository.files.read(source, 32 * 1024 * 1024);
                repository.locked(state -> {
                    try { repository.files.atomicWrite(target, data); return null; }
                    catch (java.io.IOException e) { throw LocalRequestException.storeUnavailable(); }
                });
                return target;
            } finally {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException | UnsupportedOperationException e) { throw LocalRequestException.storeUnavailable(); }
    }
    @Override public void deleteDriver(String databaseType, String version) {
        type(databaseType);
        repository.locked(state -> {
            var target = listInstalledDrivers(databaseType).stream().filter(d -> Objects.equals(d.getVersion(), version)).findFirst()
                    .orElseThrow(() -> new LocalRequestException(404, "LOCAL_DRIVER_NOT_FOUND", "本地驱动不存在"));
            Path path = Path.of(target.getFilePath()).normalize();
            if (state.connections().stream().anyMatch(c -> sameFile(Path.of(c.driverJarPath()), path))) {
                throw new LocalRequestException(409, "LOCAL_DRIVER_IN_USE", "已有连接使用此驱动，请先修改连接配置");
            }
            try { LocalPrivateFiles.rejectLink(path); Files.delete(path); return null; }
            catch (java.io.IOException e) { throw LocalRequestException.storeUnavailable(); }
        });
    }
    private static boolean sameFile(Path left, Path right) {
        if (left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize())) return true;
        try { return Files.isSameFile(left, right); } catch (java.io.IOException e) { return false; }
    }
}
