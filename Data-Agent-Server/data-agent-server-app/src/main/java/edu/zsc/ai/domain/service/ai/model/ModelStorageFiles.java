package edu.zsc.ai.domain.service.ai.model;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Private creation attributes and permissions for POSIX filesystems and Windows ACLs. */
final class ModelStorageFiles {
    private ModelStorageFiles() {}

    static FileAttribute<?>[] attributes(Path path,boolean directory) throws IOException {
        var views=path.getFileSystem().supportedFileAttributeViews();
        if(views.contains("posix"))return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(directory?"rwx------":"rw-------"))};
        if(views.contains("acl")) {
            var acl=privateAcl(path,directory);
            return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
                public String name() { return "acl:acl"; }
                public List<AclEntry> value() { return acl; }
            }};
        }
        throw new IOException("Model storage requires POSIX permissions or ACL support");
    }

    static void ownerOnly(Path path,boolean directory) throws IOException {
        rejectLink(path);
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
            return;
        }
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("Model storage requires POSIX permissions or ACL support");
        var expected=privateAcl(path,directory);
        acl.setAcl(expected);
        var principal=expected.get(0).principal();
        if(acl.getAcl().stream().anyMatch(entry->entry.type()==AclEntryType.ALLOW && !entry.principal().equals(principal)))
            throw new IOException("Unable to restrict model storage access");
    }

    private static List<AclEntry> privateAcl(Path path,boolean directory) throws IOException {
        String user=System.getProperty("user.name");
        if(user==null || user.isBlank())throw new IOException("Model storage user is unavailable");
        var owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
        var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(directory)entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
        return List.of(entry.build());
    }

    static void createDirectories(Path path) throws IOException {
        rejectLink(path);
        Files.createDirectories(path,attributes(path,true));
        ownerOnly(path,true);
    }
    static void createDirectory(Path path) throws IOException {
        rejectLink(path);
        Files.createDirectory(path,attributes(path,true));
        ownerOnly(path,true);
    }
    static Path temporary(Path directory,String prefix) throws IOException {
        var path=Files.createTempFile(directory,prefix,".tmp",attributes(directory,false));
        try { ownerOnly(path,false);return path; }
        catch(IOException | RuntimeException error) { Files.deleteIfExists(path);throw error; }
    }
    private static void rejectLink(Path path) throws IOException {
        if(Files.isSymbolicLink(path))throw new IOException("Invalid model storage path");
    }
}
