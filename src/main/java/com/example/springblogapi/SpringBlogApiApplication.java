package com.example.springblogapi;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Boot 서버를 시작하는 클래스다.
 * 아래의 TerminalMenu는 REST API를 쉽게 연습하는 보조 메뉴라 같은 파일에 둔다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableJpaRepositories(considerNestedRepositories = true)
public class SpringBlogApiApplication {

    /** 서버를 시작하고 같은 터미널에서 숫자 메뉴를 실행한다. */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(SpringBlogApiApplication.class, args);
        try {
            new TerminalMenu().run();
        } finally {
            SpringApplication.exit(context);
        }
    }
}

/**
 * Postman 대신 API를 호출해 볼 수 있는 간단한 숫자 메뉴다.
 * 서버 API를 호출하므로 JWT 보안과 작성자 검사를 그대로 거친다.
 */
class TerminalMenu {

    private static final String SERVER_URL = "http://localhost:8080";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final Scanner scanner = new Scanner(System.in);
    private String token;
    private String loginEmail;

    /** 사용자가 0번을 선택할 때까지 메뉴를 반복한다. */
    void run() {
        System.out.println("\nSpring Boot 서버와 터미널 메뉴가 실행되었습니다.");
        while (true) {
            printMenu();
            try {
                switch (input("메뉴 번호: ")) {
                    case "1" -> signup();
                    case "2" -> login();
                    case "3" -> listPosts();
                    case "4" -> detailPost();
                    case "5" -> createPost();
                    case "6" -> updatePost();
                    case "7" -> deletePost();
                    case "8" -> logout();
                    case "0" -> {
                        System.out.println("프로그램을 종료합니다.");
                        return;
                    }
                    default -> System.out.println("0~8 사이의 번호를 입력해 주세요.");
                }
            } catch (NoSuchElementException exception) {
                return;
            } catch (ConnectException exception) {
                System.out.println("서버에 연결할 수 없습니다.");
            } catch (Exception exception) {
                System.out.println("처리 실패: " + exception.getMessage());
            }
        }
    }

    private void printMenu() {
        String state = token == null ? "로그아웃" : loginEmail + " 로그인 중";
        System.out.println("\n============================");
        System.out.println(" SecureBlog 메뉴 (" + state + ")");
        System.out.println("============================");
        System.out.println("1. 회원가입");
        System.out.println("2. 로그인");
        System.out.println("3. 게시글 목록");
        System.out.println("4. 게시글 상세");
        System.out.println("5. 게시글 작성 (로그인 필요)");
        System.out.println("6. 게시글 수정 (작성자만)");
        System.out.println("7. 게시글 삭제 (작성자만)");
        System.out.println("8. 로그아웃");
        System.out.println("0. 종료");
    }

    /** 회원가입 API를 호출한다. */
    private void signup() throws IOException, InterruptedException {
        show("회원가입", request("POST", "/api/auth/signup", Map.of(
                "email", inputEmail(),
                "password", inputNewPassword(),
                "nickname", inputRequired("닉네임: ")
        ), false));
    }

    /** 로그인 성공 시 응답의 Access Token을 저장한다. */
    private void login() throws IOException, InterruptedException {
        String email = inputEmail();
        HttpResponse<String> response = request("POST", "/api/auth/login", Map.of(
                "email", email,
                "password", inputRequired("비밀번호: ")
        ), false);
        show("로그인", response);
        if (response.statusCode() == 200) {
            token = json.readTree(response.body()).path("token").asString();
            loginEmail = email;
            System.out.println("로그인 상태가 안전하게 저장되었습니다.");
        }
    }

    private void listPosts() throws IOException, InterruptedException {
        show("게시글 목록", request("GET", "/api/posts", null, false));
    }

    private void detailPost() throws IOException, InterruptedException {
        show("게시글 상세", request("GET", "/api/posts/" + inputPostId(), null, false));
    }

    /** 저장한 JWT를 넣어 게시글을 작성한다. */
    private void createPost() throws IOException, InterruptedException {
        if (!checkLogin()) {
            return;
        }
        show("게시글 작성", request("POST", "/api/posts", Map.of(
                "title", inputRequired("제목: "),
                "content", inputRequired("내용: ")
        ), true));
    }

    /** 저장한 JWT를 넣어 본인 게시글을 수정한다. */
    private void updatePost() throws IOException, InterruptedException {
        if (!checkLogin()) {
            return;
        }
        String id = inputPostId();
        show("게시글 수정", request("PUT", "/api/posts/" + id, Map.of(
                "title", inputRequired("새 제목: "),
                "content", inputRequired("새 내용: ")
        ), true));
    }

    /** 저장한 JWT를 넣어 본인 게시글을 삭제한다. */
    private void deletePost() throws IOException, InterruptedException {
        if (!checkLogin()) {
            return;
        }
        String id = inputPostId();
        if (!input("정말 삭제할까요? (y/N): ").equalsIgnoreCase("y")) {
            System.out.println("삭제를 취소했습니다.");
            return;
        }
        show("게시글 삭제", request("DELETE", "/api/posts/" + id, null, true));
    }

    private void logout() {
        token = null;
        loginEmail = null;
        System.out.println("로그아웃되었습니다.");
    }

    /** 보호 API는 JWT가 있을 때만 메뉴에서 요청한다. */
    private boolean checkLogin() {
        if (token != null) {
            return true;
        }
        System.out.println("먼저 2번 메뉴에서 로그인해 주세요.");
        return false;
    }

    /** HTTP 메서드, 주소, JSON 본문, JWT 포함 여부로 API 요청을 만든다. */
    private HttpResponse<String> request(
            String method, String path, Map<String, String> body, boolean useToken
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL + path))
                .header("Accept", "application/json");
        if (useToken) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        }
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 서버 응답을 JSON 원문 대신 간단한 한글 결과로 출력한다. */
    private void show(String name, HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            System.out.println("\n" + name + " 실패: " + errorMessage(response.statusCode()));
            return;
        }
        if (response.body().isBlank()) {
            System.out.println("\n게시글이 정상적으로 삭제되었습니다.");
            return;
        }
        try {
            JsonNode result = json.readTree(response.body());
            switch (name) {
                case "회원가입" -> printMember("회원가입 완료", result);
                case "로그인" -> printMember("로그인 성공", result);
                case "게시글 목록" -> printPostList(result);
                default -> printPost(name + " 완료", result);
            }
        } catch (Exception exception) {
            System.out.println("\n결과를 표시하지 못했습니다. 메뉴를 다시 실행해 주세요.");
        }
    }

    private String errorMessage(int status) {
        return switch (status) {
            case 400 -> "입력 형식이 올바르지 않습니다.";
            case 401 -> "이메일·비밀번호가 틀렸거나 로그인이 필요합니다.";
            case 403 -> "작성자 본인만 수정하거나 삭제할 수 있습니다.";
            case 404 -> "해당 번호의 게시글을 찾을 수 없습니다.";
            case 409 -> "이미 가입된 이메일입니다.";
            default -> "요청을 처리하지 못했습니다.";
        };
    }

    private void printMember(String title, JsonNode member) {
        System.out.println("\n============================");
        System.out.println(" " + title);
        System.out.println("============================");
        System.out.println("회원 번호 : " + member.path("id").asString());
        System.out.println("이메일    : " + member.path("email").asString());
        System.out.println("닉네임    : " + member.path("nickname").asString());
    }

    private void printPost(String title, JsonNode post) {
        System.out.println("\n============================");
        System.out.println(" " + title);
        System.out.println("============================");
        System.out.println("글 번호 : " + post.path("id").asString());
        System.out.println("제목    : " + post.path("title").asString());
        System.out.println("내용    : " + post.path("content").asString());
        System.out.println("작성자  : " + post.path("authorNickname").asString());
    }

    private void printPostList(JsonNode posts) {
        System.out.println("\n============================");
        System.out.println(" 게시글 목록");
        System.out.println("============================");
        if (posts.isEmpty()) {
            System.out.println("아직 작성된 게시글이 없습니다.");
            return;
        }
        for (JsonNode post : posts) {
            System.out.println("[" + post.path("id").asString() + "번 글]");
            System.out.println("제목   : " + post.path("title").asString());
            System.out.println("내용   : " + post.path("content").asString());
            System.out.println("작성자 : " + post.path("authorNickname").asString());
            System.out.println("----------------------------");
        }
    }

    /** 메뉴 입력을 받되, 빈 값·이메일·비밀번호·글 번호는 먼저 간단히 검사한다. */
    private String input(String message) {
        System.out.print(message);
        if (!scanner.hasNextLine()) {
            throw new NoSuchElementException();
        }
        return scanner.nextLine().trim();
    }

    private String inputRequired(String message) {
        while (true) {
            String value = input(message);
            if (!value.isBlank()) {
                return value;
            }
            System.out.println("값을 비워 둘 수 없습니다.");
        }
    }

    private String inputEmail() {
        while (true) {
            String email = inputRequired("이메일: ");
            if (email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                return email;
            }
            System.out.println("이메일 형식이 아닙니다. 예: test@example.com");
        }
    }

    private String inputNewPassword() {
        while (true) {
            String password = inputRequired("비밀번호(4글자 이상): ");
            if (password.length() >= 4) {
                return password;
            }
            System.out.println("비밀번호는 4글자 이상 입력해 주세요.");
        }
    }

    private String inputPostId() {
        while (true) {
            String id = inputRequired("게시글 번호: ");
            if (id.matches("[1-9][0-9]*")) {
                return id;
            }
            System.out.println("게시글 번호는 1 이상의 숫자로 입력해 주세요.");
        }
    }
}
