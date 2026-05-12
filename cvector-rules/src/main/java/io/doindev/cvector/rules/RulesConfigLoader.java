package io.doindev.cvector.rules;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RulesConfigLoader {

    private RulesConfigLoader() {}

    public static RulesConfig loadOrDefault(Path rulesYml) {
        if (rulesYml == null || !Files.exists(rulesYml)) return RulesConfig.defaults();
        try (InputStream in = Files.newInputStream(rulesYml)) {
            Yaml yaml = new Yaml(new Constructor(RulesConfig.class, new LoaderOptions()));
            RulesConfig cfg = yaml.load(in);
            return cfg != null ? cfg : RulesConfig.defaults();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("failed to load " + rulesYml + ": " + e.getMessage(), e);
        }
    }

    public static String defaultYaml() {
        return """
                # cvector architecture rules.
                # Tune thresholds below; disable built-in rules by name in `disable`.
                # Define project-specific rules under `custom` using Cypher.

                thresholds:
                  godFileMethods: 30
                  godClassMethods: 20
                  longMethodLines: 80
                  deepInheritance: 5

                disable: []
                #  - long-method
                #  - dead-code

                custom:
                #  - name: no-system-out-in-services
                #    description: Service classes should not print to System.out.
                #    severity: WARN
                #    cypher: |
                #      MATCH (c:Class {projectId: $pid, isService: true})-[:CONTAINS]->(m:Method)-[:CALLS]->(callee:Method)
                #      WHERE callee.fqName CONTAINS 'PrintStream.println'
                #      RETURN c.fqName AS subject, callee.fqName AS message
                """;
    }
}
