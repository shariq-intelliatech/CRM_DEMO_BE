package com.privateinvest.crm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.privateinvest.crm.exception.ApiExceptions.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is signed in (brief §6.2: staff sign in with Microsoft 365; §6.4: every change records the user). Dev users
 * exist only for local development and tests; with them off, a request with no sign-in is refused.
 */
@DisplayName("CurrentUser: sign-in and dev users")
class CurrentUserTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String devHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (devHeader != null) {
            req.addHeader(CurrentUser.DEV_HEADER, devHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static void signIn(String user) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    @DisplayName("production (dev users off): no sign-in is refused, even with a dev header")
    void productionRefusesAnonymous() {
        request("u1");
        CurrentUser current = new CurrentUser(false);
        assertThatThrownBy(current::id).isInstanceOf(ForbiddenException.class).hasMessageContaining("Sign in");
    }

    @Test
    @DisplayName("production: an anonymous token is not a sign-in")
    void anonymousTokenIsNotASignIn() {
        request(null);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(new CurrentUser(false)::id).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a signed-in user is the current user, and a dev header can't impersonate another")
    void signedInUserWins() {
        request("u4");
        signIn("sophie.turner");
        assertThat(new CurrentUser(false).id()).isEqualTo("sophie.turner");
        assertThat(new CurrentUser(true).id()).isEqualTo("sophie.turner");
    }

    @Test
    @DisplayName("dev users: the X-Dev-User header picks the user, trimmed")
    void devHeaderPicksTheUser() {
        request("  u3 ");
        assertThat(new CurrentUser(true).id()).isEqualTo("u3");
    }

    @Test
    @DisplayName("dev users: no or blank header means u1")
    void devDefaultsToU1() {
        request(null);
        assertThat(new CurrentUser(true).id()).isEqualTo("u1");
        request("   ");
        assertThat(new CurrentUser(true).id()).isEqualTo("u1");
        RequestContextHolder.resetRequestAttributes();
        assertThat(new CurrentUser(true).id()).isEqualTo("u1");
    }
}
