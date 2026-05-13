package io.doindev.cvector.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-sent events fan-out for the dashboard. The frontend opens a single long-lived
 * connection to {@code /api/events}; the backend pushes named events whenever something
 * relevant happens: scan starts/finishes, graph mutated, etc. Frontend pollers then
 * either consume the event directly or use it as a hint to refetch a REST endpoint.
 *
 * <p>Heartbeats every 25 s keep the connection alive through proxies that aggressively
 * idle out long-poll-style streams (default in many corporate networks). The heartbeat
 * is a SSE {@code comment} so it's invisible to {@code EventSource} consumers.
 *
 * <p>Dead emitters are detected when {@link SseEmitter#send} throws {@link IOException}
 * and removed from the active set so the heartbeat loop doesn't keep hammering them.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api")
public class EventStreamController {

    /**
     * Long enough that EventSource's default reconnect (3 s) gives the server time to
     * notice broken streams, but short enough that misbehaving proxies don't time out
     * the connection mid-flight.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(25);

    /**
     * Effectively-unbounded SseEmitter timeout. The default (30 s) makes the browser
     * reconnect every half-minute even when the connection is healthy; we'd rather hold
     * it open and rely on the heartbeat for liveness.
     */
    private static final long EMITTER_TIMEOUT_MILLIS = 60L * 60L * 1000L; // 1h

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService heartbeatPool;

    @PostConstruct
    void start() {
        heartbeatPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatPool.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (heartbeatPool != null) heartbeatPool.shutdownNow();
        for (SseEmitter e : emitters) {
            try { e.complete(); } catch (RuntimeException ignored) { }
        }
        emitters.clear();
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.add(emitter);
        // Tear-down callbacks (browser closes, timeout, error) all converge on removing
        // the emitter from the active set so the heartbeat loop and event listeners
        // don't keep firing into a dead handle.
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        try {
            // Send an immediate hello event so the client can confirm the channel is open.
            emitter.send(SseEmitter.event().name("hello").data(Map.of("ok", true)));
        } catch (IOException e) {
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @EventListener
    public void onScanStatusChanged(ScanStatusChangedEvent event) {
        broadcast("scan-status", event);
    }

    @EventListener
    public void onGraphMutated(GraphMutatedEvent event) {
        broadcast("graph-mutated", event);
    }

    private void broadcast(String name, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException e) {
                emitters.remove(emitter);
                try { emitter.completeWithError(e); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                // SSE comment lines start with ':' and are ignored by EventSource — perfect
                // for keep-alives that shouldn't fire client-side handlers.
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException e) {
                emitters.remove(emitter);
                try { emitter.completeWithError(e); } catch (RuntimeException ignored) { }
            }
        }
    }
}
