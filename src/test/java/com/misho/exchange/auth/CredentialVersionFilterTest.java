package com.misho.exchange.auth;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.misho.exchange.user.DatabaseUserDetailsService.VersionedUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class CredentialVersionFilterTest {
    @Test void livenessAndStaticAssetsDoNotAcquireADatabaseDependencyEvenWithACookie()throws Exception{
        var jdbc=mock(JdbcTemplate.class);var user=mock(VersionedUser.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,java.util.List.of()));
        try{
            for(String path:java.util.List.of("/api/live","/api/health","/api/ready","/assets/app.js","/")){
                var request=new MockHttpServletRequest("GET",path);request.setServletPath(path);var response=new MockHttpServletResponse();
                new CredentialVersionFilter(jdbc).doFilter(request,response,(req,res)->res.getWriter().write("available"));
                assertEquals("available",response.getContentAsString());
            }
            verifyNoInteractions(jdbc);
        }finally{SecurityContextHolder.clearContext();}
    }
}
