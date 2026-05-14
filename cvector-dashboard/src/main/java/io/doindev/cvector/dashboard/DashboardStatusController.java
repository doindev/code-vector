package io.doindev.cvector.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proof-of-life endpoint for the dashboard UI. Returns a minimal JSON payload the SPA
 * can poll on startup to confirm the backend is reachable and report its version /
 * mode. Specific feature endpoints (monitors, schedules, settings, query) live in
 * their own controllers under {@code /api/dashboard/}.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardStatusController {

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "cvector");
        out.put("dashboardVersion", "0.0.1");
        out.put("ok", true);
        return out;
    }
}
