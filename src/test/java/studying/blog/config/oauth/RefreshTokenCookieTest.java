package studying.blog.config.oauth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCookieTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "https://ticketflow.example"})
    void issueAndDeletionUseTheSameScopeAndSecurityFlags(String origin) {
        var cookies = new RefreshTokenCookie(new OAuthLoginExchange(null, origin));
        var response = new MockHttpServletResponse();
        cookies.issue(response, "synthetic-token");
        cookies.clear(response);

        var headers = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(headers).hasSize(2).allSatisfy(header -> {
            assertThat(header).startsWith("refresh_token=").contains("Path=/", "HttpOnly", "SameSite=Lax");
            assertThat(header.contains("; Secure")).isEqualTo(origin.startsWith("https://"));
        });
        assertThat(headers.get(0)).contains("Max-Age=1209600");
        assertThat(headers.get(1)).startsWith("refresh_token=;").contains("Max-Age=0");
    }
}
