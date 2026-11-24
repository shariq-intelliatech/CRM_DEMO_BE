package com.privateinvest.crm.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Extracts Cognito Groups ("cognito:groups") and maps them to Spring Security Roles & Active Permissions.
 * Pattern mirrored directly from LMS JwtRolesAndPermissionsAuthoritiesConverter.
 */
@Component
public class JwtRolesAndPermissionsAuthoritiesConverter {

    private static final String ROLE_PREFIX = "ROLE_";

    private final JdbcClient jdbc;

    public JwtRolesAndPermissionsAuthoritiesConverter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Collection<GrantedAuthority> convertGroupsToAuthorities(List<String> cognitoGroups) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Set<String> roles = new HashSet<>();

        if (cognitoGroups != null) {
            for (String group : cognitoGroups) {
                if (group != null && !group.isBlank()) {
                    String clean = group.toUpperCase().replace("ROLE_", "");
                    roles.add(clean);
                    authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + clean));
                }
            }
        }

        // Add database-managed granular permissions associated with those roles
        Set<String> permissionKeys = getActivePermissionKeysForRoles(roles);
        for (String perm : permissionKeys) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }

        return authorities;
    }

    private Set<String> getActivePermissionKeysForRoles(Set<String> roles) {
        if (roles.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT permission FROM app_role_permission
                WHERE role_code IN (:roles)""")
                .param("roles", List.copyOf(roles))
                .query(String.class)
                .list());
    }
}
