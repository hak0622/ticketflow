package studying.blog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import studying.blog.config.oauth.OAuth2AuthorizationRequestBasedOnCookieRepository;
import studying.blog.config.oauth.OAuth2SuccessHandler;
import studying.blog.config.oauth.OAuth2UserCustomService;
import studying.blog.repository.RefreshTokenRepository;
import studying.blog.service.UserService;

import java.io.IOException;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class WebOAuthSecurityConfig {

    private final OAuth2UserCustomService oAuth2UserCustomService;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserService userService;

    @Value("${oauth2.redirect-url}")
    private String oauth2RedirectUrl;

    @Bean
    public WebSecurityCustomizer configure() {
        return web -> web.ignoring()
                .requestMatchers("/img/**","/css/**","/js/**");
    }

    // ✅ 1) API 전용 체인: JWT + STATELESS
    @Bean
    @org.springframework.core.annotation.Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**");

        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.addFilterBefore(tokenAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/token", "/api/test-support/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/concerts", "/api/concerts/*").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/concerts").authenticated()
                .requestMatchers("/api/coupons/**").authenticated()
                .requestMatchers("/api/me/**").authenticated()
                .requestMatchers("/api/concerts/*/queue", "/api/concerts/*/queue/me").authenticated()
                .requestMatchers("/api/concerts/*/booking", "/api/concerts/*/booking/me", "/api/concerts/*/booking/detail").authenticated()
                .requestMatchers("/api/concerts/*/payment", "/api/concerts/*/payment/me", "/api/concerts/*/payment/toss-confirm").authenticated()
                .anyRequest().authenticated()
        );

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeSecurityError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeSecurityError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."))
        );

        return http.build();
    }

    // ✅ 2) 웹(Thymeleaf) 전용 체인: OAuth2 + 세션 + 로그아웃
    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        // 이 체인은 /api/** 제외한 나머지 처리
        http
                .csrf(csrf -> csrf.disable()) // (원하면 웹만 enable로 바꿔도 됨)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // ✅ 세션 허용 (중요!!)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // ✅ 로그아웃 활성화 (중요!!)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/lectures")
                );

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/lectures", "/login", "/oauth2/**").permitAll()
                .requestMatchers("/admin/**").authenticated()
                // 페이지 중 로그인 필요하면 여기서 authenticated()로 걸면 됨
                .anyRequest().permitAll()
        );

        http.oauth2Login(oauth -> oauth
                .loginPage("/login")
                .authorizationEndpoint(ep ->
                        ep.authorizationRequestRepository(oAuth2AuthorizationRequestBasedOnCookieRepository())
                )
                .successHandler(oAuth2SuccessHandler())
                .userInfoEndpoint(user -> user.userService(oAuth2UserCustomService))
        );

        return http.build();
    }

    @Bean
    public OAuth2SuccessHandler oAuth2SuccessHandler() {
        return new OAuth2SuccessHandler(
                tokenProvider,
                refreshTokenRepository,
                oAuth2AuthorizationRequestBasedOnCookieRepository(),
                userService,
                oauth2RedirectUrl
        );
    }

    @Bean
    public TokenAuthenticationFilter tokenAuthenticationFilter() {
        return new TokenAuthenticationFilter(tokenProvider);
    }

    @Bean
    public OAuth2AuthorizationRequestBasedOnCookieRepository oAuth2AuthorizationRequestBasedOnCookieRepository() {
        return new OAuth2AuthorizationRequestBasedOnCookieRepository();
    }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private void writeSecurityError(HttpServletResponse response,
                                    HttpStatus status,
                                    String error,
                                    String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        new ObjectMapper().writeValue(response.getWriter(), Map.of(
                "status", status.value(),
                "error", error,
                "message", message
        ));
    }
}
