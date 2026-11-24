package com.privateinvest.crm.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System heartbeat and baseline health endpoint for infrastructure verification.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "PrivateInvest CRM Backend",
                "javaVersion", System.getProperty("java.version"),
                "timestamp", Instant.now().toString()
        ));
    }
}
