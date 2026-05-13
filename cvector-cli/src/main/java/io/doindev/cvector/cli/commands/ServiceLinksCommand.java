package io.doindev.cvector.cli.commands;

import io.doindev.cvector.cli.CvectorRuntime;
import io.doindev.cvector.cli.output.TableRenderer;
import io.doindev.cvector.core.config.CvectorConfig;
import io.doindev.cvector.core.store.GraphStore;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "service-links", description = "Cross-service dependencies via HTTP clients, queues, and topics.", mixinStandardHelpOptions = true)
public class ServiceLinksCommand implements Callable<Integer> {

    private final CvectorRuntime runtime;

    public ServiceLinksCommand(CvectorRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        CvectorConfig cfg = runtime.loadConfig();
        CvectorConfig.ProjectEntry active = runtime.requireActiveProject(cfg);
        try (GraphStore store = runtime.openGraphStore(cfg)) {
            Map<String, List<Map<String, Object>>> links = store.serviceLinks(active.projectId());

            section("Outgoing HTTP calls (Java / TS / Python clients)");
            print(links.get("outgoingHttp"));

            section("Outgoing message-broker producers (Kafka / RabbitMQ / JMS / SQS / SNS)");
            print(links.get("outgoingMessaging"));

            section("Incoming queue/topic consumers");
            print(links.get("incomingConsumers"));

            section("Exposed REST endpoints");
            print(links.get("restEndpoints"));

            section("External database tables touched");
            print(links.get("tablesTouched"));
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
