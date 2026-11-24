package com.privateinvest.crm.record;

import java.util.Map;
import com.privateinvest.crm.exception.ApiExceptions.NotFoundException;
import com.privateinvest.crm.security.PermissionService;

/**
 * The record types the configurable-record engine serves. Each maps to a fixed table (a whitelist, never user
 * input), its names on screen and the permissions that guard it. Person and Entity share every engine: field
 * registry, rules, search, list views, one-off fields, archive and restore.
 */
public final class RecordObjects {

    public record ObjectType(
            String apiName,
            String table,
            String singular,
            String plural,
            String readPermission,
            String createPermission,
            String editPermission,
            String archivePermission,
            /** Similar-name checks compare names with company suffixes such as "Pty Ltd" removed. */
            boolean companyNames) {
    }

    public static final ObjectType PERSON = new ObjectType("person", "person", "Person", "Persons",
            PermissionService.PERSON_READ, PermissionService.PERSON_CREATE, PermissionService.PERSON_EDIT,
            PermissionService.PERSON_ARCHIVE, false);

    public static final ObjectType ENTITY = new ObjectType("entity", "entity", "Entity", "Entities",
            PermissionService.ENTITY_READ, PermissionService.ENTITY_CREATE, PermissionService.ENTITY_EDIT,
            PermissionService.ENTITY_ARCHIVE, true);

    private static final Map<String, ObjectType> ALL = Map.of(PERSON.apiName(), PERSON, ENTITY.apiName(), ENTITY);

    private RecordObjects() {
    }

    public static ObjectType get(String object) {
        ObjectType t = object == null ? null : ALL.get(object.trim().toLowerCase());
        if (t == null) {
            throw new NotFoundException("Record type", object);
        }
        return t;
    }

    public static boolean exists(String object) {
        return object != null && ALL.containsKey(object.trim().toLowerCase());
    }
}
