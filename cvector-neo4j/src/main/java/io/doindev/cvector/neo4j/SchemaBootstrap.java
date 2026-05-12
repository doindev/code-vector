package io.doindev.cvector.neo4j;

import java.util.List;
import java.util.Map;

public class SchemaBootstrap {

    public static final List<String> NODE_LABELS = List.of(
            "Project", "File", "Module", "Class", "Method", "Field",
            "Table", "Column", "ApiEndpoint", "EnvVar", "ConfigKey", "MavenDependency",
            "CssClass", "DesignToken", "CssMixin"
    );

    private final Neo4jClient client;

    public SchemaBootstrap(Neo4jClient client) {
        this.client = client;
    }

    public void bootstrap() {
        for (String label : NODE_LABELS) {
            client.write(
                    "CREATE CONSTRAINT cvector_" + label.toLowerCase() + "_id IF NOT EXISTS "
                            + "FOR (n:" + label + ") REQUIRE n.id IS UNIQUE",
                    Map.of()
            );
        }
        client.write("CREATE INDEX cvector_method_fq IF NOT EXISTS FOR (n:Method) ON (n.projectId, n.fqName)", Map.of());
        client.write("CREATE INDEX cvector_class_fq IF NOT EXISTS FOR (n:Class) ON (n.projectId, n.fqName)", Map.of());
        client.write("CREATE INDEX cvector_file_path IF NOT EXISTS FOR (n:File) ON (n.projectId, n.path)", Map.of());
    }

    public boolean hasConstraints() {
        var rows = client.read("SHOW CONSTRAINTS YIELD name WHERE name STARTS WITH 'cvector_' RETURN count(*) AS c", Map.of());
        if (rows.isEmpty()) return false;
        return rows.get(0).get("c").asLong() >= NODE_LABELS.size();
    }
}
