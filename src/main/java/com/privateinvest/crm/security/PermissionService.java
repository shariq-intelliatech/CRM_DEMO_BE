package com.privateinvest.crm.security;

import java.util.List;
import lombok.RequiredArgsConstructor;
import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Role-based permissions: users have roles (app_user_role), roles grant permissions (app_role_permission).
 * When Microsoft 365 sign-in is added, roles come from the token instead; callers don't change.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    /** Create, edit, reorder and retire custom fields (and their dropdown options). */
    public static final String CUSTOM_FIELD_MANAGE = "CUSTOM_FIELD_MANAGE";
    /** See and change values of custom fields marked sensitive. */
    public static final String CUSTOM_FIELD_SENSITIVE = "CUSTOM_FIELD_SENSITIVE";
    /** Lists, search, pickers and Person records. */
    public static final String PERSON_READ = "PERSON_READ";
    public static final String PERSON_CREATE = "PERSON_CREATE";
    public static final String PERSON_EDIT = "PERSON_EDIT";
    /** Archive and restore Persons, and see archived ones. */
    public static final String PERSON_ARCHIVE = "PERSON_ARCHIVE";
    /** Entity lists, search, pickers and records. */
    public static final String ENTITY_READ = "ENTITY_READ";
    public static final String ENTITY_CREATE = "ENTITY_CREATE";
    public static final String ENTITY_EDIT = "ENTITY_EDIT";
    /** Archive and restore Entities, and see archived ones. */
    public static final String ENTITY_ARCHIVE = "ENTITY_ARCHIVE";
    /** Create, change and delete list views everyone sees (Salesforce: Manage Public List Views). */
    public static final String LIST_VIEW_SHARE = "LIST_VIEW_SHARE";

    private static final String REQUEST_CACHE = PermissionService.class.getName() + ".permissions";

    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public List<String> roles() {
        return jdbc.sql("SELECT role_code FROM app_user_role WHERE user_id = :u ORDER BY role_code")
                .param("u", currentUser.id()).query(String.class).list();
    }

    /** The current user's permissions, read once per request. */
    @SuppressWarnings("unchecked")
    public List<String> permissions() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        String key = REQUEST_CACHE + ":" + currentUser.id();
        if (attrs != null && attrs.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof List<?> cached) {
            return (List<String>) cached;
        }
        List<String> loaded = jdbc.sql("""
                        SELECT DISTINCT rp.permission FROM app_user_role ur
                        JOIN app_role_permission rp ON rp.role_code = ur.role_code
                        WHERE ur.user_id = :u ORDER BY rp.permission""")
                .param("u", currentUser.id()).query(String.class).list();
        if (attrs != null) {
            attrs.setAttribute(key, loaded, RequestAttributes.SCOPE_REQUEST);
        }
        return loaded;
    }

    public boolean has(String permission) {
        return permissions().contains(permission);
    }

    public void require(String permission, String action) {
        if (!has(permission)) {
            throw new ForbiddenException("You don't have permission to " + action + ".");
        }
    }
}
