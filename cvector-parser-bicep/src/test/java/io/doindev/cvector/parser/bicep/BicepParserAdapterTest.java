package io.doindev.cvector.parser.bicep;

import io.doindev.cvector.core.GraphEvent;
import io.doindev.cvector.core.ProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BicepParserAdapterTest {

    @Test
    void emitsResourcesParamsAndOutputs(@TempDir Path root) throws Exception {
        Path file = root.resolve("main.bicep");
        Files.writeString(file, """
                param location string = 'eastus'
                var name = 'mystorage'

                resource storage 'Microsoft.Storage/storageAccounts@2022-09-01' = {
                  name: name
                  location: location
                  sku: {
                    name: 'Standard_LRS'
                  }
                  kind: 'StorageV2'
                }

                output storageId string = storage.id
                """);
        List<GraphEvent> events = parse(root, file);
        assertThat(nodeFqNames(events, "Resource")).contains("resource.storage");
        assertThat(nodeFqNames(events, "BicepParameter")).contains("param.location");
        assertThat(nodeFqNames(events, "BicepOutput")).contains("output.storageId");
        assertThat(nodeFqNames(events, "BicepVariable")).contains("var.name");
    }

    private static List<String> nodeFqNames(List<GraphEvent> events, String label) {
        return events.stream()
                .filter(e -> e instanceof GraphEvent.NodeUpsert u && label.equals(u.key().label()))
                .map(e -> ((GraphEvent.NodeUpsert) e).key().fqName())
                .toList();
    }

    private static List<GraphEvent> parse(Path root, Path file) {
        List<GraphEvent> events = new ArrayList<>();
        ProjectContext ctx = new ProjectContext("pid", "test", root);
        new BicepParserAdapter().parse(file, ctx, events::add);
        return events;
    }
}
