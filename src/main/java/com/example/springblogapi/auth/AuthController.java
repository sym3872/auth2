package com.example.springblogapi.auth;

import com.example.springblogapi.config.SecurityConfig.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회원가입과 로그인 API, 회원 데이터 구조를 한 파일에 모은다.
 * 기능이 작으므로 별도의 Service나 DTO 파일은 만들지 않고 record를 사용한다.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "회원가입과 로그인 API")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /** Spring이 저장소, BCrypt 암호화기, JWT 도구를 생성자로 넣어 준다. */
    public AuthController(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 이메일 중복을 확인하고 BCrypt로 암호화한 비밀번호를 저장한다. */
    @PostMapping("/signup")
    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 닉네임으로 새 회원을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일")
    })
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        User savedUser = userRepository.save(new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.nickname()
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new SignupResponse(savedUser.getId(), savedUser.getEmail(), savedUser.getNickname())
        );
    }

    /** 이메일과 BCrypt 비밀번호를 비교하고, 성공하면 Access Token을 발급한다. */
    @PostMapping("/login")
    @Operation(summary = "로그인", description = "이메일과 비밀번호를 확인하고 JWT Access Token을 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공 및 Access Token 발급"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치")
    })
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElseThrow(this::loginFailed);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw loginFailed();
        }

        return new LoginResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                jwtTokenProvider.createToken(user.getEmail())
        );
    }

    /** 이메일과 비밀번호 중 무엇이 틀렸는지 노출하지 않는 로그인 실패 응답이다. */
    private ResponseStatusException loginFailed() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    /** 회원가입 요청 JSON이다. record는 생성자와 값을 읽는 메서드를 자동으로 만든다. */
    public record SignupRequest(
            @Schema(description = "로그인에 사용할 이메일", example = "user@example.com")
            @NotBlank @Email String email,
            @Schema(description = "4글자 이상 비밀번호", example = "password1234", accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 4) String password,
            @Schema(description = "화면에 표시할 닉네임", example = "홍길동")
            @NotBlank String nickname
    ) {
    }

    /** 회원가입 뒤 비밀번호를 제외하고 반환하는 회원 정보다. */
    public record SignupResponse(
            @Schema(description = "생성된 회원 번호", example = "1") Long id,
            @Schema(description = "가입한 이메일", example = "user@example.com") String email,
            @Schema(description = "가입한 닉네임", example = "홍길동") String nickname
    ) {
    }

    /** 로그인 요청 JSON이다. */
    public record LoginRequest(
            @Schema(description = "가입한 이메일", example = "user@example.com")
            @NotBlank @Email String email,
            @Schema(description = "가입한 비밀번호", example = "password1234", accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank String password
    ) {
    }

    /** 로그인 성공 시 반환하는 회원 정보와 JWT Access Token이다. */
    public record LoginResponse(
            @Schema(description = "회원 번호", example = "1") Long id,
            @Schema(description = "회원 이메일", example = "user@example.com") String email,
            @Schema(description = "회원 닉네임", example = "홍길동") String nickname,
            @Schema(description = "보호 API 호출에 사용할 JWT Access Token") String token
    ) {
    }

    /**
     * H2의 users 테이블 한 행을 표현하는 회원 엔티티다.
     * 이 파일에서만 사용하는 단순한 회원 기능이라 컨트롤러와 함께 둔다.
     */
    @Entity
    @Table(name = "users")
    public static class User {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, unique = true)
        private String email;

        // 평문이 아니라 BCrypt로 암호화된 문자열만 저장한다.
        @Column(nullable = false)
        private String password;

        @Column(nullable = false)
        private String nickname;

        /** JPA가 데이터베이스 값으로 객체를 만들 때 필요한 기본 생성자다. */
        protected User() {
        }

        public User(String email, String password, String nickname) {
            this.email = email;
            this.password = password;
            this.nickname = nickname;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }

        public String getPassword() {
            return password;
        }

        public String getNickname() {
            return nickname;
        }
    }

    /**
     * JpaRepository가 save, findById, delete 같은 기본 DB 작업을 자동으로 제공한다.
     * 중첩 인터페이스이므로 Application의 considerNestedRepositories 설정이 필요하다.
     */
    public interface UserRepository extends JpaRepository<User, Long> {
        Optional<User> findByEmail(String email);

        boolean existsByEmail(String email);
    }
}
