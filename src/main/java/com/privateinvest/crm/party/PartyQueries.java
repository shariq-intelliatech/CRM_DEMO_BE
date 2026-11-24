package com.privateinvest.crm.party;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.customfield.CustomFieldDtos.CustomFieldDef;
import com.privateinvest.crm.party.PartyDtos.EntityPerson;
import com.privateinvest.crm.party.PartyDtos.PersonLink;
import com.privateinvest.crm.record.ObjectRules;
import com.privateinvest.crm.record.ObjectRules.Rules;
import com.privateinvest.crm.record.RecordObjects;
import com.privateinvest.crm.record.RecordQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read-side SQL for Entity links. Names, the Entity type, emails and phones are configurable fields, so each is
 * read through the rule that names it (record name, type field, duplicate-check email and mobile fields).
 */
@Component
@RequiredArgsConstructor
public class PartyQueries {

    private final JdbcClient jdbc;
    private final ObjectRules rules;
    private final RecordQueries records;

    /** A Person's Entity links, primary first, with each Entity's name and type (the type is inherited, brief §3.4). */
    public List<PersonLink> linksOfPerson(UUID personId) {
        Rules er = rules.effective("entity");
        Map<String, Object> params = new HashMap<>();
        params.put("id", personId);
        String name = RecordQueries.nameExpression("en", er.nameFields(), params, "n");
        String type = er.typeField() == null ? "NULL" : "en.custom_fields ->> :typeKey";
        if (er.typeField() != null) {
            params.put("typeKey", er.typeField());
        }
        return jdbc.sql("""
                        SELECT l.id, l.entity_id, %s AS entity_name, %s AS entity_type, l.role, l.job_title, l.department,
                               l.date_linked, l.email, l.is_primary
                        FROM entity_link l JOIN entity en ON en.id = l.entity_id
                        WHERE l.person_id = :id AND en.archived_at IS NULL
                        ORDER BY l.is_primary DESC, 3""".formatted(name, type))
                .params(params)
                .query((rs, i) -> new PersonLink(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class),
                        rs.getString(9), rs.getBoolean(10)))
                .list();
    }

    /** An Entity's linked Persons, with each Person's primary email and phone. */
    public List<EntityPerson> personsOfEntity(UUID entityId) {
        Rules pr = rules.effective("person");
        Map<String, CustomFieldDef> defs = records.defs(RecordObjects.PERSON);
        Map<String, Object> params = new HashMap<>();
        params.put("id", entityId);
        String name = RecordQueries.nameExpression("p", pr.nameFields(), params, "n");
        String email = RecordQueries.valueExpression("p", defs.get(pr.duplicateCheck().emailField()), params, "emailKey");
        String phone = RecordQueries.valueExpression("p", defs.get(pr.duplicateCheck().mobileField()), params, "phoneKey");
        return jdbc.sql("""
                        SELECT l.id, p.id, %s, %s, %s, l.role, l.job_title, l.department, l.date_linked, l.is_primary
                        FROM entity_link l JOIN person p ON p.id = l.person_id
                        WHERE l.entity_id = :id AND p.archived_at IS NULL
                        ORDER BY 3, p.id""".formatted(name, email, phone))
                .params(params)
                .query((rs, i) -> new EntityPerson(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getObject(9, LocalDate.class), rs.getBoolean(10)))
                .list();
    }

    /** The types of the live Entities a Person links to: what "$linkedEntityType" conditions read. */
    public List<String> linkedEntityTypes(UUID personId) {
        return linksOfPerson(personId).stream().map(PersonLink::entityType).filter(t -> t != null).distinct().toList();
    }
}
