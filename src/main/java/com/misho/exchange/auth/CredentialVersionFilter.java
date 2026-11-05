package com.misho.exchange.auth;

import com.misho.exchange.user.DatabaseUserDetailsService.VersionedUser;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revokes existing sessions on their next request after a password reset. */
public class CredentialVersionFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    public CredentialVersionFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        return java.util.Set.of("/api/live","/api/health","/api/ready").contains(path)
                || !(path.startsWith("/api/") || path.startsWith("/actuator/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof VersionedUser user){
            var versions=jdbc.queryForList("SELECT auth_version FROM exchange.users WHERE username=?",Long.class,user.getUsername());
            if(versions.isEmpty() || versions.get(0)!=user.version()){
                SecurityContextHolder.clearContext();
                if(request.getSession(false)!=null)request.getSession(false).invalidate();
                response.addHeader("Set-Cookie", "VERDEX_REMEMBER_ME=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"+(request.isSecure()?"; Secure":""));
                response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"Your password changed. Please sign in again.\",\"code\":\"AUTHENTICATION_REQUIRED\"}");return;
            }
        }
        chain.doFilter(request,response);
    }
}
