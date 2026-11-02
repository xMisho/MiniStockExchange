package com.misho.exchange.auth;

import com.misho.exchange.user.DatabaseUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.CookieTheftException;
import org.springframework.stereotype.Component;

@Component
public class RememberedLoginService extends PersistentTokenBasedRememberMeServices {
    public RememberedLoginService(DatabaseUserDetailsService users, RememberMeTokenRepository tokens, Environment environment) {
        super(UUID.randomUUID().toString(), users, tokens);
        setCookieName("VERDEX_REMEMBER_ME");
        setParameter("remember-me");
        setTokenValiditySeconds(14 * 24 * 60 * 60);
        setUseSecureCookie(environment.getProperty("SESSION_COOKIE_SECURE", Boolean.class, false));
        setCookieCustomizer(cookie -> { cookie.setHttpOnly(true); cookie.setAttribute("SameSite", "Lax"); });
    }
    @Override
    public void loginSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        if (!rememberMeRequested(request, getParameter())) cancelCookie(request, response);
        super.loginSuccess(request, response, authentication);
    }
    @Override
    public Authentication autoLogin(HttpServletRequest request, HttpServletResponse response) {
        if (!"GET".equals(request.getMethod()) || !"/api/auth/me".equals(request.getServletPath())) return null;
        try { return super.autoLogin(request, response); }
        catch (CookieTheftException exception) { cancelCookie(request, response); return null; }
    }
}
