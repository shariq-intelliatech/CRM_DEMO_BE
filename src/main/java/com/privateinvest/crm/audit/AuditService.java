package com.privateinvest.crm.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.security.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Writes the append-only audit trail: who changed what, with old and new values (brief §6.4). */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CurrentUser currentUser;

    public void record(String recordKind, UUID recordId, String action, Object before, Object after, String reason) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (before != null) {
            changes.put("before", before);
        }
        if (after != null) {
            changes.put("after", after);
        }
        jdbc.sql("""
                        INSERT INTO audit_event (record_kind, record_id, action, changes, reason, user_id)
                        VALUES (:kind, :id, :action, CAST(:changes AS jsonb), :reason, :user)
                        """)
                .param("kind", recordKind)
                .param("id", recordId)
                .param("action", action)
                .param("changes", json.writeValueAsString(changes))
                .param("reason", reason)
                .param("user", currentUser.id())
                .update();
    }
}
