package com.privateinvest.crm.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-side DTOs for record history and the admin audit log (brief §6.4). */
public final class AuditDtos {

    private AuditDtos() {
    }

    /** One field-level or property-level diff inside an audit entry. */
    public record AuditChange(
            String field,
            String label,
            Object before,
            Object after,
            boolean sensitive,
            boolean redacted) {
    }

    /** One formatted audit event, with sensitive field values masked unless the caller is permitted. */
    public record AuditEntry(
            long id,
            String recordKind,
            UUID recordId,
            String action,
            String targetLabel,
            List<AuditChange> changes,
            Map<String, Object> details,
            String reason,
            String userId,
            String userName,
            String userInitials,
            OffsetDateTime occurredAt) {
    }

    /** Paginated history response. */
    public record AuditPage(
            List<AuditEntry> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
