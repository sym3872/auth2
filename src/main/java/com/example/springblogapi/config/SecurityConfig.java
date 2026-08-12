package com.example.springblogapi.config;

import com.example.springblogapi.auth.AuthController.User;
import com.example.springblogapi.auth.AuthController.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security, JWT, Swagger 설정을 한 파일에 모은다.
 * 파일 수는 줄이되 비밀번호 암호화와 JWT 검증, 작성자 권한 검사는 그대로 유지한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Swagger의 보호 API가 참조할 JWT Bearer 인증 방식 이름이다. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** 회원가입 비밀번호를 BCrypt로 암호화하고 로그인 때 비교할 도구다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** application.yml의 비밀키와 만료 시간으로 JWT 발급·검증 도구를 만든다. */
    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${jwt.secret}") String base64Secret,
            @Value("${jwt.expiration-ms}") long expirationMilliseconds
    ) {
        return new JwtTokenProvider(base64Secret, expirationMilliseconds);
    }

    /** Swagger UI의 제목, 서버 주소, JWT 입력 방식을 설정한다. */
    @Bean
    public OpenAPI openAPI() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("로그인 응답의 token 값만 입력하면 Bearer 접두사는 자동으로 붙습니다.");

        return new OpenAPI()
                .info(new Info()
                        .title("SecureBlog API")
                        .version("1.1.0")
                        .description("JWT Access Token을 사용하는 학습용 블로그 API입니다.")
                        .license(new License().name("학습용 프로젝트")))
                .addServersItem(new Server().url("http://localhost:8080").description("로컬 개발 서버"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, bearer));
    }

    /**
     * 공개 주소, 로그인 필수 주소, 세션 정책, JWT 필터 순서를 설정한다.
     * POST·PUT·DELETE 게시글 API는 anyRequest에 남겨 반드시 인증을 거치게 한다.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtTokenProvider jwtTokenProvider, UserRepository userRepository
    ) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository);

        // REST API는 서버 세션이 아니라 요청마다 JWT를 검사한다.
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.exceptionHandling(exception ->
                exception.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
        );

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                        "/api/auth/**",
                        "/h2-console/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/error"
                ).permitAll()
                // GET만 공개하므로 글 작성·수정·삭제에는 이 규칙이 적용되지 않는다.
                .requestMatchers(HttpMethod.GET, "/api/posts", "/api/posts/**").permitAll()
                .anyRequest().authenticated()
        );

        // H2 콘솔은 iframe을 사용하므로 같은 사이트 안에서만 frame을 허용한다.
        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        // URL 권한 검사 전에 JWT로 로그인 User를 복원한다.
        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** JWT를 만들고 서명·만료 시간을 검증하는 작은 도구다. */
    public static class JwtTokenProvider {

        private final SecretKey signingKey;
        private final long expirationMilliseconds;

        public JwtTokenProvider(String base64Secret, long expirationMilliseconds) {
            signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
            this.expirationMilliseconds = expirationMilliseconds;
        }

        /** 이메일을 subject로 넣고 현재 시각과 만료 시각에 서명한 Access Token을 만든다. */
        public String createToken(String email) {
            Date now = new Date();
            return Jwts.builder()
                    .subject(email)
                    .issuedAt(now)
                    .expiration(new Date(now.getTime() + expirationMilliseconds))
                    .signWith(signingKey)
                    .compact();
        }

        /** 위조되었거나 만료된 토큰은 JwtException을 발생시키고, 정상 토큰의 이메일을 반환한다. */
        public String getEmail(String token) {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        }
    }

    /**
     * 매 요청의 Authorization: Bearer 토큰을 검사하는 필터다.
     * 정상 토큰의 이메일로 실제 회원을 찾은 뒤 SecurityContext에 User를 저장한다.
     */
    private static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private final JwtTokenProvider jwtTokenProvider;
        private final UserRepository userRepository;

        private JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
            this.jwtTokenProvider = jwtTokenProvider;
            this.userRepository = userRepository;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            String header = request.getHeader("Authorization");

            // 공개 요청 또는 Bearer 형식이 아닌 요청은 인증을 만들지 않고 다음 단계로 넘긴다.
            if (header == null || !header.startsWith("Bearer ")) {
                filterChain.doFilter(request, response);
                return;
            }

            try {
                String email = jwtTokenProvider.getEmail(header.substring(7));
                User user = userRepository.findByEmail(email).orElse(null);

                if (user != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(user, null, List.of())
                    );
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 잘못된 토큰은 로그인으로 인정하지 않는다. 보호 주소에서는 이후 401이 반환된다.
            }

            filterChain.doFilter(request, response);
        }
    }
}
