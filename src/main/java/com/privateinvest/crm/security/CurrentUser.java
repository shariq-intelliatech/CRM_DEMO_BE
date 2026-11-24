package com.privateinvest.crm.security;

import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The signed-in user's id (an {@code app_user.id}).
 * <ul>
 *   <li>When the request is authenticated (Cognito / Microsoft 365 token), the principal's name is the user.</li>
 *   <li>Otherwise, only while {@code crm.auth.dev-users} is on (local development and tests), the caller picks a
 *       seeded dev user with the {@code X-Dev-User} header, defaulting to {@code u1}.</li>
 *   <li>With dev users off and no sign-in, the request is refused.</li>
 * </ul>
 */
@Component
public class CurrentUser {

    public static final String DEV_HEADER = "X-Dev-User";
    private static final String DEFAULT_DEV_USER = "u1";

    private final boolean devUsers;

    public CurrentUser(@Value("${crm.auth.dev-users:false}") boolean devUsers) {
        this.devUsers = devUsers;
    }

    public String id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        if (devUsers) {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                String header = attrs.getRequest().getHeader(DEV_HEADER);
                if (header != null && !header.isBlank()) {
                    return header.trim();
                }
            }
            return DEFAULT_DEV_USER;
        }
        throw new ForbiddenException("Sign in to use the CRM.");
    }
}
