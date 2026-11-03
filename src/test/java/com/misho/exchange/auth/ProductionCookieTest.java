package com.misho.exchange.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.misho.exchange.user.DatabaseUserDetailsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class ProductionCookieTest {
    @Test
    void rememberedCookieUsesTheResolvedSecureSessionSetting() {
        for (boolean secure : List.of(true, false)) {
            var service = new RememberedLoginService(mock(DatabaseUserDetailsService.class),
                    mock(RememberMeTokenRepository.class), new MockEnvironment()
                    .withProperty("server.servlet.session.cookie.secure", Boolean.toString(secure)));
            var request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setParameter("remember-me", "true");
            var response = new MockHttpServletResponse();
            service.loginSuccess(request, response,
                    UsernamePasswordAuthenticationToken.authenticated("test_user", null, List.of()));
            String cookie = response.getHeader("Set-Cookie");
            assertNotNull(cookie);
            assertEquals(secure, cookie.contains("Secure"));
            assertTrue(cookie.contains("HttpOnly"));
            assertEquals("Lax", response.getCookie("VERDEX_REMEMBER_ME").getAttribute("SameSite"));
        }
    }
}
