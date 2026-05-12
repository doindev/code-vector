package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.neo4j.Neo4jClient;
import io.doindev.cvector.neo4j.repo.GraphQueries;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "service-links", description = "Cross-service dependencies via HTTP clients, queues, and topics.")
public class ServiceLinksCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public ServiceLinksCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        String pid = active.projectId();

        try (Neo4jClient client = runtime.openNeo4j(cfg)) {
            GraphQueries q = new GraphQueries(client);

            section("Outgoing HTTP calls (Java / TS / Python clients)");
            print(q.raw(
                    "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                            + "WHERE callee.fqName =~ '(?i).*(RestTemplate|WebClient|HttpClient|FeignClient|OkHttpClient"
                            + "|axios|fetch|external::requests|external::httpx|external::urllib3|external::aiohttp).*' "
                            + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                            + "ORDER BY calls DESC LIMIT 50",
                    Map.of("pid", pid)
            ));

            section("Outgoing message-broker producers (Kafka / RabbitMQ / JMS / SQS / SNS)");
            print(q.raw(
                    "MATCH (m:Method {projectId: $pid})-[:CALLS]->(callee:Method) "
                            + "WHERE callee.fqName =~ '(?i).*(KafkaTemplate|RabbitTemplate|JmsTemplate|StreamBridge|SqsTemplate|SnsTemplate).*' "
                            + "RETURN m.fqName AS caller, callee.fqName AS target, count(*) AS calls "
                            + "ORDER BY calls DESC LIMIT 50",
                    Map.of("pid", pid)
            ));

            section("Incoming queue/topic consumers (@KafkaListener / @RabbitListener / @JmsListener / @SqsListener)");
            print(q.raw(
                    "MATCH (m:Method {projectId: $pid}) "
                            + "WHERE coalesce(m.isQueueListener, false) = true "
                            + "RETURN m.fqName AS handler, m.fileId AS fileId LIMIT 100",
                    Map.of("pid", pid)
            ));

            section("Exposed REST endpoints");
            print(q.raw(
                    "MATCH (e:ApiEndpoint {projectId: $pid}) "
                            + "OPTIONAL MATCH (e)-[:HANDLES]->(m:Method) "
                            + "RETURN e.httpMethod AS method, e.path AS path, "
                            + "coalesce(m.fqName, '(inline)') AS handler, "
                            + "coalesce(e.framework, 'spring') AS framework "
                            + "ORDER BY path",
                    Map.of("pid", pid)
            ));

            section("External database tables touched");
            print(q.raw(
                    "MATCH (n)-[r:READS_TABLE|WRITES_TABLE]->(t:Table {projectId: $pid}) "
                            + "RETURN t.name AS table, type(r) AS access, count(DISTINCT n) AS sources "
                            + "ORDER BY t.name",
                    Map.of("pid", pid)
            ));
        }
        return 0;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void print(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        TableRenderer.render(System.out, rows);
    }
}
