package studying.blog.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import static org.springframework.boot.autoconfigure.security.servlet.PathRequest.toH2Console;

@Configuration
@RequiredArgsConstructor
public class WebOAuthSecurityConfig {

    private final OAuth2UserCustomService oAuth2UserCustomService;
    private final TokenProvider tokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserService userService;

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
                .requestMatchers("/api/token").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
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
                userService
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
}
