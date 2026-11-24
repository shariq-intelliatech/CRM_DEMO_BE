package com.privateinvest.crm.audit;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditDtos.AuditPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoint for the CRM audit trail (Setup → Audit log). Individual record history lives on
 * {@code GET /api/persons/{id}/history} and {@code GET /api/entities/{id}/history}.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService query;

    @GetMapping
    AuditPage list(
            @RequestParam(required = false) String recordKind,
            @RequestParam(required = false) UUID recordId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return query.search(recordKind, recordId, action, userId, page, size);
    }
}
