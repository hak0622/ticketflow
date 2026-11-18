package studying.blog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import studying.blog.BlogApplication;
import studying.blog.config.TokenProvider;
import studying.blog.domain.Role;
import studying.blog.domain.User;
import studying.blog.repository.RefreshTokenRepository;
import studying.blog.repository.UserRepository;
import studying.blog.service.RefreshTokenService;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
class OAuthSessionIntegrationTest {
    private static final String FRONTEND_URL = "http://localhost:5173";
    private static final String START = "/oauth2/authorization/google";
    private static final String CALLBACK = "/login/oauth2/code/google";
    private static final String EXCHANGE = "/oauth2/exchange";
    private static final String ROLE_EMAIL = "oauth-role@example.test";
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final AtomicInteger USER_INFO_CALLS = new AtomicInteger();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("oauth_test")
            .withCommand("--skip-log-bin");

    private static HttpServer provider;
    private static ServletWebServerApplicationContext appA;
    private static ServletWebServerApplicationContext appB;

    @BeforeAll
    static void startApps() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        provider.createContext("/token", exchange -> {
            TOKEN_CALLS.incrementAndGet();
            respond(exchange, """
                    {"access_token":"provider-test-access","token_type":"Bearer","expires_in":3600}
                    """);
        });
        provider.createContext("/userinfo", exchange -> {
            USER_INFO_CALLS.incrementAndGet();
            respond(exchange, """
                    {"sub":"fake-provider-subject","email":"%s","name":"OAuth Test User"}
                    """.formatted(ROLE_EMAIL));
        });
        provider.start();
        appA = startApp();
        appB = startApp();
    }

    @AfterAll
    static void stopApps() {
        if (appB != null) appB.close();
        if (appA != null) appA.close();
        if (provider != null) provider.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void aRealSessionCookieWorksAcrossAppsAndDoesNotReplaceBearerAuth(String role) throws Exception {
        var users = appA.getBean(UserRepository.class);
        User user = users.findByEmail(ROLE_EMAIL)
                .orElseGet(() -> User.builder().email(ROLE_EMAIL).nickname("OAuth Test User").build());
        user.setRole(Role.valueOf(role));
        users.save(user);

        var browser = new Browser();
        var flow = start(browser);
        int tokenCalls = TOKEN_CALLS.get();
        int userInfoCalls = USER_INFO_CALLS.get();
        var response = callback(browser, appA, "code=test-code&state=" + flow.state());
        assertCleanCallback(response);
        String jwt = issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
        assertThat(TOKEN_CALLS.get()).isEqualTo(tokenCalls + 1);
        assertThat(USER_INFO_CALLS.get()).isEqualTo(userInfoCalls + 1);
        assertThat(appA.getBean(RefreshTokenRepository.class).findByUserId(user.getId())).isPresent();
        assertExchangeStatus(browser.exchange(appA, FRONTEND_URL), 401);

        var sessionOnly = browser.get(appB, "/api/admin/concerts");
        assertThat(sessionOnly.statusCode()).isEqualTo(401);
        var authenticated = browser.get(appB, "/api/admin/concerts", "Authorization", "Bearer " + jwt);
        assertThat(authenticated.statusCode()).isEqualTo(role.equals("ADMIN") ? 200 : 403);

        String refreshToken = browser.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("refresh_token"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
        assertThat(browser.get(appB, "/api/admin/concerts", "Authorization", "Bearer " + refreshToken)
                .statusCode()).isEqualTo(401);
        var json = appB.getBean(ObjectMapper.class);
        var reissueResponse = browser.renew(appB, FRONTEND_URL);
        assertThat(reissueResponse.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(reissueResponse.statusCode()).isEqualTo(201);
        String newAccessToken = json.readTree(reissueResponse.body()).get("accessToken").asText();
        assertThat(browser.get(appB, "/api/admin/concerts", "Authorization", "Bearer " + newAccessToken)
                .statusCode()).isEqualTo(role.equals("ADMIN") ? 200 : 403);

        assertRejected(() -> callback(browser, appB, "code=test-code&state=" + flow.state()));
    }

    @Test
    void missingSessionAndLegacyCookieCannotCompleteLogin() throws Exception {
        var flow = start(new Browser());
        assertRejected(() -> new Browser().get(appA, CALLBACK + "?code=test-code&state=" + flow.state(),
                "Cookie", "oauth2_auth_request=legacy-cookie"));
    }

    @Test
    void expiredRedisSessionCannotCompleteLogin() throws Exception {
        var browser = new Browser();
        var flow = start(browser);
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("SESSION", flow.cookieValue()));
        String sessionId = appA.getBean(CookieSerializer.class).readCookieValues(request).get(0);
        SessionRepository<?> sessions = appA.getBean(SessionRepository.class);
        expire(sessions, sessionId);
        assertRejected(() -> callback(browser, appB, "code=test-code&state=" + flow.state()));
    }

    @Test
    void missingOrOldStateDoesNotConsumeTheLatestRequest() throws Exception {
        var browser = new Browser();
        var first = start(browser);
        var latest = start(browser);
        assertThat(latest.state()).isNotEqualTo(first.state());
        assertRejected(() -> callback(browser, appB, "code=test-code"));
        assertRejected(() -> callback(browser, appB, "code=test-code&state=" + first.state()));
        var response = callback(browser, appB, "code=test-code&state=" + latest.state());
        assertCleanCallback(response);
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void providerCancellationConsumesTheMatchingRequestWithoutIssuingTokens() throws Exception {
        var browser = new Browser();
        var flow = start(browser);
        assertRejected(() -> callback(browser, appB, "error=access_denied&state=" + flow.state()));
        assertRejected(() -> callback(browser, appB, "code=test-code&state=" + flow.state()));
        var retry = start(browser);
        var response = callback(browser, appB, "code=test-code&state=" + retry.state());
        assertCleanCallback(response);
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void rejectsInvalidOriginOtherSessionsAndGetWithoutConsumingTheExchange() throws Exception {
        var unrelatedSession = new Browser();
        login(unrelatedSession);
        issuedAccessToken(unrelatedSession.exchange(appA, FRONTEND_URL));
        var browser = new Browser();
        login(browser);

        assertExchangeStatus(browser.exchange(appB, null), 403);
        assertExchangeStatus(browser.exchange(appB, "http://localhost:8080"), 403);
        assertExchangeStatus(browser.exchange(appB, "https://untrusted.example"), 403);
        assertExchangeStatus(new Browser().exchange(appB, FRONTEND_URL), 401);
        assertExchangeStatus(unrelatedSession.exchange(appB, FRONTEND_URL), 401);
        assertThat(browser.get(appB, EXCHANGE).statusCode()).isEqualTo(405);
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void logoutRevokesTheRefreshTokenAndTheOAuthSession() throws Exception {
        var browser = new Browser();
        var callback = login(browser);
        String token = browser.refreshToken();
        assertRefreshCookie(callback, false);
        assertThat(reissue(token).statusCode()).isEqualTo(201);

        var logout = browser.logout(appA, FRONTEND_URL);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertRefreshCookie(logout, true);
        assertThat(reissue(token).statusCode()).isEqualTo(401);
        assertExchangeStatus(browser.exchange(appB, FRONTEND_URL), 401);
        assertThat(browser.logout(appB, FRONTEND_URL).statusCode()).isEqualTo(204);
    }

    @Test
    void getAndOffOriginLogoutLeaveTheSessionAndTokenUsable() throws Exception {
        var browser = new Browser();
        login(browser);
        String token = browser.refreshToken();
        assertThat(browser.get(appA, "/logout").statusCode()).isEqualTo(405);
        assertThat(browser.logout(appB, null).statusCode()).isEqualTo(403);
        assertThat(browser.logout(appA, "https://untrusted.example").statusCode()).isEqualTo(403);
        assertThat(reissue(token).statusCode()).isEqualTo(201);
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void logoutWithAnOlderBrowserCookieDoesNotRevokeTheReplacementToken() throws Exception {
        var olderBrowser = new Browser();
        login(olderBrowser);
        String original = olderBrowser.refreshToken();
        var newerBrowser = new Browser();
        login(newerBrowser);
        String replacement = newerBrowser.refreshToken();
        assertThat(original.equals(replacement)).isFalse();

        assertThat(olderBrowser.logout(appB, FRONTEND_URL).statusCode()).isEqualTo(204);
        assertThat(reissue(original).statusCode()).isEqualTo(401);
        assertThat(reissue(replacement).statusCode()).isEqualTo(201);
        issuedAccessToken(newerBrowser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void oldLogoutWaitingForTheRefreshRowRechecksTheCommittedValue() throws Exception {
        var browser = new Browser();
        login(browser);
        String original = browser.refreshToken();
        var user = appA.getBean(UserRepository.class).findByEmail(ROLE_EMAIL).orElseThrow();
        var replacement = appA.getBean(TokenProvider.class).generateRefreshToken(user, Duration.ofDays(14));
        var transaction = new TransactionTemplate(appA.getBean(PlatformTransactionManager.class));
        var jdbc = appA.getBean(JdbcTemplate.class);
        var worker = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> logout = transaction.execute(status -> {
                appA.getBean(RefreshTokenService.class).saveForLogin(user.getId(), replacement);
                var request = worker.submit(() -> browser.logout(appB, FRONTEND_URL));
                assertWaitingOnRefreshRow(jdbc, request);
                return request;
            });
            assertThat(logout.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
        } finally {
            worker.shutdownNow();
        }
        assertThat(reissue(original).statusCode()).isEqualTo(401);
        assertThat(reissue(replacement).statusCode()).isEqualTo(201);
    }

    private static void assertWaitingOnRefreshRow(JdbcTemplate jdbc, Future<?> logout) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline) && !logout.isDone()) {
            boolean waiting = jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row ->
                    row.get("Info") instanceof String sql
                            && sql.toLowerCase(Locale.ROOT).contains("from refresh_token")
                            && sql.toLowerCase(Locale.ROOT).contains("for update"));
            if (waiting) return;
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        throw new AssertionError("The logout did not reach the refresh row lock");
    }

    @Test
    void databaseRevocationFailureKeepsTheCookieAndSessionForRetry() throws Exception {
        var browser = new Browser();
        login(browser);
        String token = browser.refreshToken();
        var jdbc = appA.getBean(JdbcTemplate.class);
        jdbc.execute("""
                CREATE TRIGGER reject_refresh_delete BEFORE DELETE ON refresh_token
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test revocation failure'
                """);
        try {
            var failed = browser.logout(appB, FRONTEND_URL);
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(failed.headers().allValues("set-cookie"))
                    .noneMatch(value -> value.startsWith("refresh_token="));
            assertThat(reissue(token).statusCode()).isEqualTo(201);
            issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
        } finally {
            jdbc.execute("DROP TRIGGER reject_refresh_delete");
        }
        assertThat(browser.logout(appB, FRONTEND_URL).statusCode()).isEqualTo(204);
        assertThat(reissue(token).statusCode()).isEqualTo(401);
    }

    @Test
    void redisExpiryEndsTheExchangeWhileTheGoogleSessionRemains() throws Exception {
        var redis = appA.getBean(StringRedisTemplate.class);
        var existing = redis.keys("oauth:exchange:*");
        var browser = new Browser();
        login(browser);
        var newKeys = redis.keys("oauth:exchange:*");
        newKeys.removeAll(existing);
        assertThat(newKeys).hasSize(1);
        String key = newKeys.iterator().next();
        assertThat(redis.getExpire(key)).isBetween(1L, 60L);
        assertThat(redis.expire(key, Duration.ZERO)).isTrue();
        assertExchangeStatus(browser.exchange(appB, FRONTEND_URL), 401);
    }

    @Test
    void oneExchangeWinsAcrossTwoApps() throws Exception {
        var browser = new Browser();
        login(browser);
        var pool = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return browser.exchange(appA, FRONTEND_URL); });
            var second = pool.submit(() -> { start.await(); return browser.exchange(appB, FRONTEND_URL); });
            start.countDown();
            var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 401);
            for (var response : responses) {
                if (response.statusCode() == 200) issuedAccessToken(response);
                else assertExchangeStatus(response, 401);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRedisExchangeCommandFailureReturns503AndDoesNotIssueAToken() throws Exception {
        var browser = new Browser();
        login(browser);
        assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-getdel").getStdout().strip())
                .isEqualTo("OK");
        try {
            assertExchangeStatus(browser.exchange(appB, FRONTEND_URL), 503);
        } finally {
            assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+getdel").getStdout().strip())
                    .isEqualTo("OK");
        }
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    @Test
    void failedRedisPreparationRedirectsToLoginInsteadOfTheExchangePage() throws Exception {
        var browser = new Browser();
        var flow = start(browser);
        assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-set").getStdout().strip())
                .isEqualTo("OK");
        try {
            var response = callback(browser, appA, "code=test-code&state=" + flow.state());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(location(response)).isEqualTo(FRONTEND_URL + "/login?error=oauth");
            assertThat(response.headers().allValues("set-cookie"))
                    .noneMatch(cookie -> cookie.startsWith("refresh_token="));
        } finally {
            assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+set").getStdout().strip())
                    .isEqualTo("OK");
        }
        assertExchangeStatus(browser.exchange(appB, FRONTEND_URL), 401);
    }

    @Test
    void lostExchangeResponseRequiresANewLogin() throws Exception {
        var browser = new Browser();
        login(browser);
        var completed = new AtomicInteger();
        var proxy = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        proxy.createContext(EXCHANGE, request -> {
            try (request) {
                var response = browser.exchange(appB, FRONTEND_URL);
                completed.set(response.statusCode());
                // Drop the HTTP response after the backend consumed the record.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        proxy.start();
        try {
            var address = URI.create("http://localhost:" + proxy.getAddress().getPort() + EXCHANGE);
            var request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThatThrownBy(() -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(IOException.class);
        } finally {
            proxy.stop(0);
        }
        assertThat(completed.get()).isEqualTo(200);
        assertExchangeStatus(browser.exchange(appA, FRONTEND_URL), 401);
        login(browser);
        issuedAccessToken(browser.exchange(appB, FRONTEND_URL));
    }

    private static HttpResponse<String> login(Browser browser) throws Exception {
        var flow = start(browser);
        var response = callback(browser, appA, "code=test-code&state=" + flow.state());
        assertCleanCallback(response);
        return response;
    }

    private static HttpResponse<String> reissue(String refreshToken) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(address(appA) + "/api/token"))
                .timeout(Duration.ofSeconds(10)).header("Origin", FRONTEND_URL)
                .header("Cookie", "refresh_token=" + refreshToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRefreshCookie(HttpResponse<?> response, boolean deleted) {
        var cookie = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("refresh_token=")).findFirst().orElseThrow();
        assertThat(cookie.contains("HttpOnly")).isTrue();
        assertThat(cookie.contains("SameSite=Lax")).isTrue();
        assertThat(cookie.contains("Path=/")).isTrue();
        assertThat(cookie.contains("; Secure")).isFalse();
        assertThat(cookie.contains(deleted ? "Max-Age=0" : "Max-Age=1209600")).isTrue();
    }

    private static void assertCleanCallback(HttpResponse<?> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).isEqualTo(FRONTEND_URL + "/auth/callback");
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    private static void assertExchangeStatus(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        if (status != 200) assertThat(response.body()).doesNotContain("accessToken");
    }

    private static String issuedAccessToken(HttpResponse<String> response) throws IOException {
        assertExchangeStatus(response, 200);
        String token = appA.getBean(ObjectMapper.class).readTree(response.body()).path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private static ServletWebServerApplicationContext startApp() {
        String prefix = "spring.security.oauth2.client.provider.google.";
        String providerUrl = "http://localhost:" + provider.getAddress().getPort();
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BlogApplication.class)
                .run("--spring.profiles.active=test", "--server.port=0",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.jpa.show-sql=false", "--logging.level.p6spy=OFF",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--app.test-support.enabled=false",
                        "--spring.session.redis.namespace=ticketflow:test:oauth", "--spring.session.timeout=30m",
                        "--oauth2.redirect-url=" + FRONTEND_URL, "--FRONTEND_URL=" + FRONTEND_URL,
                        "--" + prefix + "authorization-uri=" + providerUrl + "/authorize",
                        "--" + prefix + "token-uri=" + providerUrl + "/token",
                        "--" + prefix + "user-info-uri=" + providerUrl + "/userinfo");
    }

    private static Flow start(Browser browser) throws Exception {
        var response = browser.get(appA, START);
        assertThat(response.statusCode()).isEqualTo(302);
        URI authorization = URI.create(location(response));
        assertThat(authorization.getPort()).isEqualTo(provider.getAddress().getPort());
        assertThat(query(authorization, "redirect_uri")).isEqualTo(address(appA) + CALLBACK);
        var cookies = response.headers().allValues("set-cookie");
        assertThat(cookies).noneMatch(cookie -> cookie.startsWith("oauth2_auth_request="));
        var sessionCookie = browser.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow();
        assertThat(sessionCookie.isHttpOnly()).isTrue();
        String state = query(authorization, "state");
        assertThat(state).isNotBlank();
        return new Flow(state, sessionCookie.getValue());
    }

    private static HttpResponse<String> callback(Browser browser, ServletWebServerApplicationContext app, String query)
            throws Exception {
        return browser.get(app, CALLBACK + "?" + query);
    }

    private static <S extends Session> void expire(SessionRepository<S> repository, String sessionId) {
        S session = repository.findById(sessionId);
        assertThat(session).isNotNull();
        session.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(31)));
        repository.save(session);
        assertThat(repository.findById(sessionId)).isNull();
    }

    private static void assertRejected(Callback callback) throws Exception {
        int tokenCalls = TOKEN_CALLS.get();
        int userInfoCalls = USER_INFO_CALLS.get();
        long users = appA.getBean(UserRepository.class).count();
        long refreshTokens = appA.getBean(RefreshTokenRepository.class).count();
        var response = callback.send();
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(location(response)).isEqualTo(FRONTEND_URL + "/login?error=oauth");
        assertThat(response.headers().allValues("set-cookie"))
                .noneMatch(cookie -> cookie.startsWith("refresh_token="));
        assertThat(TOKEN_CALLS.get()).isEqualTo(tokenCalls);
        assertThat(USER_INFO_CALLS.get()).isEqualTo(userInfoCalls);
        assertThat(appA.getBean(UserRepository.class).count()).isEqualTo(users);
        assertThat(appA.getBean(RefreshTokenRepository.class).count()).isEqualTo(refreshTokens);
    }

    private static String address(ServletWebServerApplicationContext app) {
        return "http://localhost:" + app.getWebServer().getPort();
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("location").orElseThrow();
    }

    private static String query(URI uri, String name) {
        if (uri.getRawQuery() == null) return null;
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .filter(parts -> parts.length == 2 && parts[0].equals(name))
                .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst().orElse(null);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @FunctionalInterface
    private interface Callback {
        HttpResponse<String> send() throws Exception;
    }

    private record Flow(String state, String cookieValue) {}

    private static final class Browser {
        private final CookieManager cookies = new CookieManager();
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).cookieHandler(cookies).build();

        CookieManager cookies() {
            return cookies;
        }

        HttpResponse<String> get(ServletWebServerApplicationContext app, String path, String... headers)
                throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(address(app) + path)).timeout(Duration.ofSeconds(10));
            if (headers.length > 0) request.headers(headers);
            return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> renew(ServletWebServerApplicationContext app, String origin)
                throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(address(app) + "/api/token")).timeout(Duration.ofSeconds(10));
            if (origin != null) request.header("Origin", origin);
            return client.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> exchange(ServletWebServerApplicationContext app, String origin)
                throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(address(app) + EXCHANGE)).timeout(Duration.ofSeconds(10));
            if (origin != null) request.header("Origin", origin);
            return client.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> logout(ServletWebServerApplicationContext app, String origin)
                throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(address(app) + "/logout")).timeout(Duration.ofSeconds(10));
            if (origin != null) request.header("Origin", origin);
            return client.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }

        String refreshToken() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("refresh_token"))
                    .findFirst().map(HttpCookie::getValue).orElseThrow();
        }
    }
}
