package com.privateinvest.crm.party.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.audit.AuditService;
import com.privateinvest.crm.exception.ApiExceptions.FieldValidationException;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.CurrentUser;
import com.privateinvest.crm.common.util.Formats;
import com.privateinvest.crm.security.PermissionService;
import com.privateinvest.crm.metadata.MetadataService;
import com.privateinvest.crm.party.PartyDtos.LinkCreate;
import com.privateinvest.crm.party.PartyDtos.LinkUpdate;
import com.privateinvest.crm.record.ObjectRules;
import com.privateinvest.crm.record.RecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entity links. A Person's first link becomes the primary one; reports read the inherited Entity type
 * from the primary link (brief §3.4, F.10). An email typed at a link is also added to the Person's emails.
 */
@Service
@RequiredArgsConstructor
public class EntityLinkService {

    private static final String KIND = "entity_link";
    /** The label an email added through a link takes in the Person's email list. */
    private static final String LINK_EMAIL_LABEL = "BUSINESS";

    private final EntityLinkRepository links;
    private final RecordService records;
    private final ObjectRules rules;
    private final MetadataService metadata;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final PermissionService permissions;

    @Transactional
    public UUID create(LinkCreate c) {
        permissions.require(PermissionService.PERSON_EDIT, "change Persons");
        records.requireLive("person", c.personId());
        records.requireLive("entity", c.entityId());
        Map<String, String> errors = new LinkedHashMap<>();
        metadata.checkCode(errors, "role", "ENTITY_LINK_ROLE", c.role(), null);
        if (links.existsByPersonIdAndEntityId(c.personId(), c.entityId())) {
            errors.put("entityId", "This Person is already linked to that Entity.");
        }
        String email = Formats.clean(c.email());
        if (email != null && !Formats.isValidEmail(email)) {
            errors.put("email", "This email address is not in a valid format.");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        addEmail(c.personId(), email);

        boolean first = links.findByPersonId(c.personId()).isEmpty();
        boolean primary = c.primary() || first;
        if (primary) {
            links.clearPrimary(c.personId());
        }
        EntityLink l = new EntityLink();
        l.setPersonId(c.personId());
        l.setEntityId(c.entityId());
        l.setRole(c.role());
        l.setJobTitle(Formats.clean(c.jobTitle()));
        l.setDepartment(Formats.clean(c.department()));
        l.setDateLinked(c.dateLinked() == null ? LocalDate.now() : c.dateLinked());
        l.setEmail(email);
        l.setPrimary(primary);
        l.setCreatedAt(OffsetDateTime.now());
        l.setCreatedBy(currentUser.id());
        links.saveAndFlush(l);
        audit.record(KIND, l.getId(), "create", null, c, null);
        return l.getId();
    }

    @Transactional
    public void update(UUID id, LinkUpdate u) {
        permissions.require(PermissionService.PERSON_EDIT, "change Persons");
        EntityLink l = find(id);
        Map<String, String> errors = new LinkedHashMap<>();
        metadata.checkCode(errors, "role", "ENTITY_LINK_ROLE", u.role(), l.getRole());
        String email = Formats.clean(u.email());
        if (email != null && !Formats.isValidEmail(email)) {
            errors.put("email", "This email address is not in a valid format.");
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
        addEmail(l.getPersonId(), email);
        Map<String, Object> before = linkSnapshot(l.getPersonId(), l.getEntityId(),
                l.getRole(), l.getJobTitle(), l.getDepartment(), l.getEmail());
        l.setRole(u.role());
        l.setJobTitle(Formats.clean(u.jobTitle()));
        l.setDepartment(Formats.clean(u.department()));
        l.setEmail(email);
        links.saveAndFlush(l);
        Map<String, Object> after = linkSnapshot(l.getPersonId(), l.getEntityId(),
                l.getRole(), l.getJobTitle(), l.getDepartment(), l.getEmail());
        audit.record(KIND, id, "update", before, after, null);
    }

    @Transactional
    public void makePrimary(UUID id) {
        permissions.require(PermissionService.PERSON_EDIT, "change Persons");
        EntityLink l = find(id);
        links.clearPrimary(l.getPersonId());
        EntityLink fresh = find(id);
        fresh.setPrimary(true);
        links.saveAndFlush(fresh);
        audit.record(KIND, id, "make-primary", null,
                Map.of("personId", l.getPersonId(), "entityId", l.getEntityId()), null);
    }

    /**
     * Unlinks a Person from an Entity. Neither record changes; the audit trail keeps the link. When the primary
     * link goes, the Person's oldest remaining link becomes primary.
     */
    @Transactional
    public void delete(UUID id) {
        permissions.require(PermissionService.PERSON_EDIT, "change Persons");
        EntityLink l = find(id);
        links.delete(l);
        links.flush();
        if (l.isPrimary()) {
            links.findByPersonId(l.getPersonId()).stream()
                    .min(Comparator.comparing(EntityLink::getCreatedAt))
                    .ifPresent(next -> {
                        next.setPrimary(true);
                        links.saveAndFlush(next);
                    });
        }
        audit.record(KIND, id, "delete", Map.of("personId", l.getPersonId(), "entityId", l.getEntityId(),
                "role", l.getRole()), null, null);
    }

    private void addEmail(UUID personId, String email) {
        if (email != null) {
            records.ensureListValue("person", personId, rules.effective("person").duplicateCheck().emailField(), email,
                    LINK_EMAIL_LABEL);
        }
    }

    private EntityLink find(UUID id) {
        return links.findById(id).orElseThrow(() -> new NotFoundException("Entity link", id));
    }

    private static Map<String, Object> linkSnapshot(UUID personId, UUID entityId, String role,
            String jobTitle, String department, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("personId", personId);
        m.put("entityId", entityId);
        m.put("role", role);
        if (jobTitle != null) {
            m.put("jobTitle", jobTitle);
        }
        if (department != null) {
            m.put("department", department);
        }
        if (email != null) {
            m.put("email", email);
        }
        return m;
    }
}
