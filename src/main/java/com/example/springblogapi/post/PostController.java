package com.example.springblogapi.post;

import com.example.springblogapi.auth.AuthController.User;
import com.example.springblogapi.config.SecurityConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 게시글 CRUD API와 게시글 데이터 구조를 한 파일에 모은다.
 * 서비스 클래스를 따로 만들지 않고 컨트롤러가 Repository를 직접 사용해 흐름을 짧게 유지한다.
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "Post", description = "게시글 CRUD API")
public class PostController {

    private final PostRepository postRepository;

    public PostController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    /** 로그인 사용자를 작성자로 넣어 새 게시글을 저장한다. */
    @PostMapping
    @Operation(summary = "게시글 작성", description = "로그인한 회원을 작성자로 하여 게시글을 생성합니다.")
    @SecurityRequirement(name = SecurityConfig.BEARER_AUTH)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "게시글 작성 성공"),
            @ApiResponse(responseCode = "400", description = "제목 또는 내용 입력값 오류"),
            @ApiResponse(responseCode = "401", description = "JWT 인증 필요")
    })
    public ResponseEntity<PostResponse> createPost(@Valid @RequestBody CreatePostRequest request) {
        Post savedPost = postRepository.save(
                new Post(request.title(), request.content(), currentUser())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PostResponse.from(savedPost));
    }

    /** 누구나 게시글 목록을 볼 수 있다. LAZY 작성자 정보를 읽기 위해 트랜잭션을 연다. */
    @GetMapping
    @Operation(summary = "게시글 목록 조회", description = "로그인 없이 모든 게시글을 조회합니다.")
    @ApiResponse(responseCode = "200", description = "게시글 목록 조회 성공")
    @Transactional(readOnly = true)
    public List<PostResponse> getPosts() {
        return postRepository.findAll().stream().map(PostResponse::from).toList();
    }

    /** 게시글 한 건을 공개 조회한다. */
    @GetMapping("/{id}")
    @Operation(summary = "게시글 상세 조회", description = "게시글 번호로 한 건의 내용을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시글 상세 조회 성공"),
            @ApiResponse(responseCode = "404", description = "게시글 없음")
    })
    @Transactional(readOnly = true)
    public PostResponse getPost(@PathVariable Long id) {
        return PostResponse.from(findPost(id));
    }

    /** 로그인한 작성자 본인만 제목과 본문을 수정할 수 있다. */
    @PutMapping("/{id}")
    @Operation(summary = "게시글 수정", description = "작성자 본인만 제목과 내용을 수정할 수 있습니다.")
    @SecurityRequirement(name = SecurityConfig.BEARER_AUTH)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "게시글 수정 성공"),
            @ApiResponse(responseCode = "400", description = "제목 또는 내용 입력값 오류"),
            @ApiResponse(responseCode = "401", description = "JWT 인증 필요"),
            @ApiResponse(responseCode = "403", description = "작성자 본인이 아님"),
            @ApiResponse(responseCode = "404", description = "게시글 없음")
    })
    @Transactional
    public PostResponse updatePost(@PathVariable Long id, @Valid @RequestBody UpdatePostRequest request) {
        Post post = findPost(id);
        checkWriter(post);
        post.update(request.title(), request.content());
        return PostResponse.from(post);
    }

    /** 로그인한 작성자 본인만 게시글을 삭제할 수 있다. */
    @DeleteMapping("/{id}")
    @Operation(summary = "게시글 삭제", description = "작성자 본인만 게시글을 삭제할 수 있습니다.")
    @SecurityRequirement(name = SecurityConfig.BEARER_AUTH)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "게시글 삭제 성공"),
            @ApiResponse(responseCode = "401", description = "JWT 인증 필요"),
            @ApiResponse(responseCode = "403", description = "작성자 본인이 아님"),
            @ApiResponse(responseCode = "404", description = "게시글 없음")
    })
    @Transactional
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        Post post = findPost(id);
        checkWriter(post);
        postRepository.delete(post);
        return ResponseEntity.noContent().build();
    }

    /** 없는 글은 바로 404로 응답해 이후 코드가 null을 사용하지 않게 한다. */
    private Post findPost(Long id) {
        return postRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다. id=" + id)
        );
    }

    /** JWT 필터가 SecurityContext에 넣은 로그인 User를 꺼낸다. */
    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return user;
    }

    /** JWT의 회원 번호와 게시글 작성자 번호가 같은지 비교해 타인의 변경을 막는다. */
    private void checkWriter(Post post) {
        if (!post.getAuthor().getId().equals(currentUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "작성자 본인만 수정하거나 삭제할 수 있습니다.");
        }
    }

    /** 게시글 작성 요청 JSON이다. */
    public record CreatePostRequest(
            @Schema(description = "게시글 제목", example = "첫 번째 글")
            @NotBlank String title,
            @Schema(description = "게시글 본문", example = "JWT로 작성한 첫 번째 게시글입니다.")
            @NotBlank String content
    ) {
    }

    /** 게시글 수정 요청 JSON이다. */
    public record UpdatePostRequest(
            @Schema(description = "수정할 게시글 제목", example = "수정한 글")
            @NotBlank String title,
            @Schema(description = "수정할 게시글 본문", example = "작성자 본인만 수정할 수 있습니다.")
            @NotBlank String content
    ) {
    }

    /** API 응답에 필요한 게시글과 작성자 공개 정보만 담는다. */
    public record PostResponse(
            @Schema(description = "게시글 번호", example = "1") Long id,
            @Schema(description = "게시글 제목", example = "첫 번째 글") String title,
            @Schema(description = "게시글 본문", example = "JWT로 작성한 첫 번째 게시글입니다.") String content,
            @Schema(description = "작성자 회원 번호", example = "1") Long authorId,
            @Schema(description = "작성자 닉네임", example = "홍길동") String authorNickname
    ) {
        public static PostResponse from(Post post) {
            return new PostResponse(
                    post.getId(),
                    post.getTitle(),
                    post.getContent(),
                    post.getAuthor().getId(),
                    post.getAuthor().getNickname()
            );
        }
    }

    /**
     * H2의 posts 테이블 한 행을 표현한다.
     * 작성자 정보는 필요할 때만 읽는 LAZY 관계로 둔다.
     */
    @Entity
    @Table(name = "posts")
    public static class Post {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false, length = 100)
        private String title;

        @Column(nullable = false, columnDefinition = "TEXT")
        private String content;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "author_id", nullable = false)
        private User author;

        /** JPA가 데이터베이스 값으로 객체를 만들 때 필요한 기본 생성자다. */
        protected Post() {
        }

        public Post(String title, String content, User author) {
            this.title = title;
            this.content = content;
            this.author = author;
        }

        /** 작성자는 유지하고 제목과 본문만 바꾼다. */
        public void update(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getContent() {
            return content;
        }

        public User getAuthor() {
            return author;
        }
    }

    /** JpaRepository가 기본 CRUD 메서드를 자동으로 제공하는 게시글 저장소다. */
    public interface PostRepository extends JpaRepository<Post, Long> {
    }
}
