package edu.zsc.ai.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Owner-only POSIX or Windows ACL files. Unsupported protection/atomic replacement fails closed. */
public class PrivateFiles {
    public final Path root;
    private final AtomicReplace replace;

    public PrivateFiles(Path configured) { this(configured, (source, target) -> Files.move(source, target,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)); }
    public PrivateFiles(Path configured, AtomicReplace replace) { root = configured.toAbsolutePath().normalize(); this.replace = replace; }
    @FunctionalInterface public interface AtomicReplace { void move(Path source, Path target) throws IOException; }

    public void directory(Path path) throws IOException {
        rejectLink(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            // Find an existing parent to determine the filesystem's protection mechanism.
            Path existing = path.getParent();
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) throw new IOException("No parent filesystem");
            Files.createDirectories(path, creationAttributes(existing, true));
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory");
        protect(path, true);
    }

    public FileAttribute<?>[] creationAttributes(Path parent, boolean directory) throws IOException {
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))};
        }
        if (Files.getFileAttributeView(parent, AclFileAttributeView.class) != null) {
            UserPrincipal owner = parent.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            List<AclEntry> acl = privateAcl(owner, directory);
            return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return acl; }
            }};
        }
        throw new IOException("Private filesystem permissions unavailable");
    }

    public void protect(Path path, boolean directory) throws IOException {
        rejectLink(path);
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Private filesystem permissions unavailable");
        acl.setAcl(privateAcl(acl.getOwner(), directory));
    }

    private static List<AclEntry> privateAcl(UserPrincipal owner, boolean directory) {
        var builder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) builder.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        return List.of(builder.build());
    }

    public byte[] read(Path path, int maxBytes) throws IOException {
        rejectLink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
        protect(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new IOException("File exceeds limit");
            return data;
        }
    }

    public void atomicWrite(Path target, byte[] bytes) throws IOException {
        rejectLink(target);
        Path temporary = Files.createTempFile(target.getParent(), ".local-", ".tmp", creationAttributes(target.getParent(), false));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No fallback to a non-atomic write. Callers hold the stable repository OS lock.
            rejectLink(target);
            replace.move(temporary, target);
            // Directory fsync is not supported by the Windows provider. File contents were already forced.
            try (var directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            catch (IOException | UnsupportedOperationException ignored) { }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link not allowed");
    }
}
