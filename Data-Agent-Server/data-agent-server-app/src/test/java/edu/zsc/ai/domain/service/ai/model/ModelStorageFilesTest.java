package edu.zsc.ai.domain.service.ai.model;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.ai.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class ModelStorageFilesTest {
    @TempDir Path temporary;

    @Test void windowsCreationAttributesUseAnOwnerAclInsteadOfUnsupportedPosixPermissions() throws Exception {
        try(var windows=Jimfs.newFileSystem(Configuration.windows().toBuilder().setAttributeViews("basic","owner","acl").build())) {
            var root=windows.getPath("C:\\Users\\developer\\.data-agent\\model-config");
            Files.createDirectories(root.getParent());
            // This is the exact unsupported attribute passed by the previous implementation.
            assertThrows(UnsupportedOperationException.class,()->Files.createDirectory(root,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))));
            for(boolean directory:new boolean[]{true,false}) {
                var attributes=ModelStorageFiles.attributes(root,directory);
                assertEquals(1,attributes.length);assertEquals("acl:acl",attributes[0].name());
                var entries=(List<?>)attributes[0].value();assertEquals(1,entries.size());
                var entry=assertInstanceOf(AclEntry.class,entries.get(0));
                assertEquals(AclEntryType.ALLOW,entry.type());
                assertEquals(windows.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")),entry.principal());
                assertEquals(directory,entry.flags().contains(AclEntryFlag.FILE_INHERIT));
            }
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void nativeWindowsStorageCanInitializeAndRestoreItsKey() throws Exception {
        exerciseStorage(temporary.resolve("model-config"));
    }

    @Test @EnabledOnOs({OS.MAC,OS.LINUX})
    void posixStorageRetainsItsPrivateCreationAndRestorationBehavior() throws Exception {
        exerciseStorage(temporary.resolve("model-config"));
    }

    private static void exerciseStorage(Path root) throws Exception {
        var properties=new MultiProviderProperties();properties.getModelRouting().setStorageDirectory(root.toString());
        var scope=new ModelAccessContext(1,WorkspaceTypeEnum.PERSONAL,1);
        var environment=new MockEnvironment(){@Override public Map<String,Object> getSystemEnvironment(){return Map.of();}};
        var files=new FileModelStore(properties);
        assertEquals(0L,files.read(scope,state->state.revision).longValue());
        var cipher=new ModelCredentialCipher(environment,properties);
        assertTrue(cipher.configured());
        byte[] original=Files.readAllBytes(root.resolve(".keys/V1.key"));
        var credentials=new FileCredentialStore(files,cipher);
        String id=files.update(scope,state->credentials.create(state,scope,"synthetic-windows-api-key",1));
        Path target=root.resolve("PERSONAL/1/models.json");
        assertFalse(Files.readString(target).contains("synthetic-windows-api-key"));
        var restartedCipher=new ModelCredentialCipher(environment,properties);
        var restartedStore=new FileModelStore(properties);
        var credential=restartedStore.read(scope,state->state.credentials.get(id));
        assertEquals("synthetic-windows-api-key",restartedCipher.decrypt(credential.encryptedPayload(),scope,id,credential.keyVersion()));
        assertArrayEquals(original,Files.readAllBytes(root.resolve(".keys/V1.key")));
        assertEquals(1L,restartedStore.read(scope,state->state.revision).longValue());
        restartedStore.update(scope,state->{state.startupImported=true;return null;});
        assertEquals(2L,files.read(scope,state->state.revision).longValue());
        for(var path:List.of(root,root.resolve(".keys"),root.resolve("PERSONAL"),target.getParent()))assertPrivate(path,true);
        for(var path:List.of(target,target.resolveSibling("models.lock"),root.resolve(".keys/V1.key"),
                root.resolve(".keys/V1.initialized"),root.resolve(".keys/keys.lock")))assertPrivate(path,false);
        Files.delete(root.resolve(".keys/V1.key"));
        assertFalse(new ModelCredentialCipher(environment,properties).configured());
        assertFalse(Files.exists(root.resolve(".keys/V1.key"))); // Lost keys must not be silently regenerated.
    }

    @Test void aclPermissionsRepairExistingBroadPermissions() throws Exception {
        try(var windows=Jimfs.newFileSystem(Configuration.windows().toBuilder().setAttributeViews("basic","owner","acl").build())) {
            // Jimfs does not implement ACL creation attributes; real Windows uses the native test above.
            var directory=windows.getPath("C:\\private-models");Files.createDirectory(directory);
            ModelStorageFiles.ownerOnly(directory,true);assertPrivate(directory,true);
            var path=Files.createTempFile(directory,".fixture-",".tmp");
            ModelStorageFiles.ownerOnly(path,false);
            assertPrivate(path,false);
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
            var other=windows.getUserPrincipalLookupService().lookupPrincipalByName("other-user");
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
                    .setPermissions(AclEntryPermission.READ_DATA).build()));
            ModelStorageFiles.ownerOnly(path,false);assertPrivate(path,false);
        }
    }

    @Test void filesystemsWithoutPrivatePermissionsFailClosedAndSymlinksRemainRejected() throws Exception {
        try(var basic=Jimfs.newFileSystem(Configuration.windows())) {
            assertThrows(IOException.class,()->ModelStorageFiles.createDirectories(basic.getPath("C:\\model-config")));
        }
        try(var windows=Jimfs.newFileSystem(Configuration.windows().toBuilder().setAttributeViews("basic","owner","acl").build())) {
            Path outside=windows.getPath("C:\\outside");Files.createDirectory(outside);
            Path link=windows.getPath("C:\\model-link");Files.createSymbolicLink(link,outside);
            assertThrows(IOException.class,()->ModelStorageFiles.createDirectories(link));
        }
    }

    static void assertPrivate(Path path,boolean directory) throws Exception {
        if(path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"),Files.getPosixFilePermissions(path));
        } else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            assertNotNull(acl);
            var entries=acl.getAcl();assertFalse(entries.isEmpty());
            var owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            for(var entry:entries)if(entry.type()==AclEntryType.ALLOW)assertEquals(owner,entry.principal());
            assertTrue(entries.stream().anyMatch(entry->entry.type()==AclEntryType.ALLOW
                    && entry.permissions().containsAll(Set.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA))));
        }
    }
}
