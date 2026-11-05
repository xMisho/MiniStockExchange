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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public RememberedLoginService(DatabaseUserDetailsService users, RememberMeTokenRepository tokens, Environment environment,
            org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        super(UUID.randomUUID().toString(), users, tokens);
        this.jdbc=jdbc;this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        setCookieName("VERDEX_REMEMBER_ME");
        setParameter("remember-me");
        setTokenValiditySeconds(14 * 24 * 60 * 60);
        setUseSecureCookie(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
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
        try {
            String cookie=extractRememberMeCookie(request);
            if(cookie==null)return null;
            String[] parts=decodeCookie(cookie);
            if(parts.length!=2)return super.autoLogin(request,response);
            var usernames=jdbc.queryForList("SELECT username FROM exchange.persistent_logins WHERE series=?",String.class,parts[0]);
            if(usernames.isEmpty())return super.autoLogin(request,response);
            return tx.execute(status->{
                // Same lock as password reset: a remembered token cannot authenticate across revocation.
                jdbc.queryForList("SELECT id FROM exchange.users WHERE username=? FOR UPDATE",Long.class,usernames.get(0));
                try { return super.autoLogin(request,response); }
                catch (CookieTheftException exception) { cancelCookie(request,response); return null; }
            });
        }
        catch (org.springframework.security.web.authentication.rememberme.InvalidCookieException exception) { cancelCookie(request,response); return null; }
        catch (CookieTheftException exception) { cancelCookie(request, response); return null; }
    }
}
