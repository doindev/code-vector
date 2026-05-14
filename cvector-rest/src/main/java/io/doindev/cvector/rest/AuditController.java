package io.doindev.cvector.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST surface for the OSV vulnerability scanner. {@code GET} reports the current cached
 * result, {@code POST /scan} kicks off a background scan (no-op if one is already running).
 * The dashboard polls {@code GET} while scanning and renders findings from the final result.
 */
@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> audit() {
        return service.snapshot();
    }

    @PostMapping("/scan")
    public Map<String, Object> scan() {
        return service.startScan();
    }
}
