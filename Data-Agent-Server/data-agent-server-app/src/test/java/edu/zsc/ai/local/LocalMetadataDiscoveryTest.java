package edu.zsc.ai.local;

import edu.zsc.ai.domain.service.agent.ChatReferenceService;
import edu.zsc.ai.domain.service.db.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalMetadataDiscoveryTest {
    final LocalConnectionService connections=mock(LocalConnectionService.class);
    final DatabaseService databases=mock(DatabaseService.class);
    final SchemaService schemas=mock(SchemaService.class);
    final LocalMetadataTools tools=new LocalMetadataTools(connections,mock(ChatReferenceService.class),
            mock(TableService.class),mock(ColumnService.class),databases,schemas);

    @Test void anEmptyWorkspaceIsDiscoverableWithoutOpeningAnyDatabase() {
        when(connections.list()).thenReturn(List.of());
        var result=tools.listConnections(new HashMap<>());
        assertThat(result).containsEntry("connections",List.of()).containsEntry("total",0).containsEntry("truncated",false);
        verify(connections).list(); verifyNoMoreInteractions(connections); verifyNoInteractions(databases,schemas);
    }

    @Test void discoveryIsBoundedExposesOnlyIdentitiesAndDoesNotReplacePinnedVersions() throws Exception {
        var sources=new ArrayList<LocalConnectionRecord.PublicView>();
        for(long id=1;id<=60;id++)sources.add(new LocalConnectionRecord(id,2,"Source "+id,"mysql","private-host",3306,
                "private-database","private-user","/private/driver.jar",30,Map.of("private-option","PRIVATE_VALUE"),
                "credential-ref",Instant.EPOCH,Instant.EPOCH).publicView());
        when(connections.list()).thenReturn(sources);
        var versions=new HashMap<Long,Long>();versions.put(1L,1L);
        var result=tools.listConnections(versions);
        assertThat(result).containsEntry("total",60).containsEntry("truncated",true);
        var json=LocalConnectionRepository.JSON.valueToTree(result);
        assertThat(json.path("connections")).hasSize(50);
        for(var source:json.path("connections"))assertThat(source.properties().stream().map(Map.Entry::getKey))
                .containsExactlyInAnyOrder("connectionId","name","dbType");
        assertThat(json.toString()).doesNotContain("private-","PRIVATE_VALUE","credential-ref");
        assertThat(versions).containsEntry(1L,1L).containsEntry(2L,2L).doesNotContainKey(51L);
        verifyNoInteractions(databases,schemas);
    }
}
