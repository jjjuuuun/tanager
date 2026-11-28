package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 명령 · 인수 · URL 에서 토큰류만 지우는 규칙(#212). 비밀값 표식은 모두 합성이다. */
class SecretMaskTest {

    private static final String M = SecretMask.MASK;

    @Test
    @DisplayName("--api-key 다음 인수는 가리고 --port 3000 은 그대로 둔다")
    void flagValue() {
        // when
        String line =
                SecretMask.commandLine("node", List.of("server.js", "--api-key", "S3CR3T-FAKE-5", "--port", "3000"));

        // then
        assertThat(line).isEqualTo("node server.js --api-key " + M + " --port 3000");
    }

    @Test
    @DisplayName("--token=값 · --password=값 처럼 = 로 붙은 값도 가린다")
    void flagWithEquals() {
        // when
        String line = SecretMask.commandLine(
                "srv", List.of("--token=S3CR3T-FAKE-1", "--client-secret=S3CR3T-FAKE-2", "--mode=fast"));

        // then
        assertThat(line).isEqualTo("srv --token=" + M + " --client-secret=" + M + " --mode=fast");
    }

    @Test
    @DisplayName("이름에 key · token · secret · password · auth 가 든 NAME=값 은 값을 가린다")
    void assignment() {
        // when
        String line =
                SecretMask.commandLine("env", List.of("API_KEY=S3CR3T-FAKE-1", "LOG_LEVEL=debug", "node", "x.js"));

        // then
        assertThat(line).isEqualTo("env API_KEY=" + M + " LOG_LEVEL=debug node x.js");
    }

    @Test
    @DisplayName("Bearer 뒤의 값을 가린다 — 같은 인수 안이든 다음 인수든")
    void bearer() {
        // when
        String inside = SecretMask.redact("Authorization: Bearer S3CR3T-FAKE-4");
        String next = SecretMask.commandLine("curl", List.of("-H", "Bearer", "S3CR3T-FAKE-4"));

        // then
        assertThat(inside).isEqualTo("Authorization: Bearer " + M);
        assertThat(next).isEqualTo("curl -H Bearer " + M);
    }

    @Test
    @DisplayName("인수 안의 URL 은 사용자 정보와 비밀 이름의 쿼리 값을 가리고 나머지는 둔다")
    void urlInArgument() {
        // when
        String redacted = SecretMask.redact("https://user:S3CR3T-FAKE-2@api.example.com/v1?token=S3CR3T-FAKE-3&page=2");

        // then
        assertThat(redacted).isEqualTo("https://" + M + "@api.example.com/v1?token=" + M + "&page=2");
    }

    @Test
    @DisplayName("32자 이상 글자 · 숫자가 섞인 고엔트로피 토큰은 가린다")
    void highEntropyToken() {
        // given
        String token = "ghp_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3z";

        // when
        String line = SecretMask.commandLine("gh-mcp", List.of(token, "--verbose"));

        // then
        assertThat(line).isEqualTo("gh-mcp " + M + " --verbose");
    }

    @Test
    @DisplayName("패키지 이름 · 경로처럼 긴 일반 글자는 가리지 않는다")
    void ordinaryWords() {
        // when
        String line = SecretMask.commandLine(
                "npx",
                List.of(
                        "-y",
                        "@modelcontextprotocol/server-filesystem",
                        "/home/dev/projects/some-long-directory-name"));

        // then
        assertThat(line)
                .isEqualTo(
                        "npx -y @modelcontextprotocol/server-filesystem /home/dev/projects/some-long-directory-name");
    }

    @Test
    @DisplayName("비밀 인수 자리의 ${VAR} 참조는 값이 아니라 이름이라 그대로 두고, 기본값이 붙은 참조는 가린다")
    void variableReference() {
        // when
        String plain = SecretMask.commandLine("srv", List.of("--api-key", "${API_KEY}"));
        String withDefault = SecretMask.commandLine("srv", List.of("--api-key", "${API_KEY:-S3CR3T-FAKE-1}"));

        // then
        assertThat(plain).isEqualTo("srv --api-key ${API_KEY}");
        assertThat(withDefault).isEqualTo("srv --api-key " + M);
    }

    @Test
    @DisplayName("command 자체에 공백으로 든 인수도 같은 규칙으로 가린다")
    void commandWithSpaces() {
        // when
        String line = SecretMask.commandLine("server --api-key S3CR3T-FAKE-5", List.of());

        // then
        assertThat(line).isEqualTo("server --api-key " + M);
    }

    @Test
    @DisplayName("URL 은 스킴 · 호스트 · 포트만 남기고 사용자 정보 · 경로 · 쿼리를 버린다")
    void origin() {
        // when / then
        assertThat(SecretMask.origin("https://user:S3CR3T-FAKE-2@api.example.com:8443/v1?token=S3CR3T-FAKE-3"))
                .contains("https://api.example.com:8443");
        assertThat(SecretMask.origin("HTTPS://Mcp.Example.com/mcp")).contains("https://mcp.example.com");
        assertThat(SecretMask.origin("http://[::1]:3000/sse")).contains("http://[::1]:3000");
    }

    @Test
    @DisplayName("URL 로 읽을 수 없거나(${VAR} 등) 호스트가 없으면 empty 다")
    void originUnparsable() {
        // when / then
        assertThat(SecretMask.origin("${API_BASE_URL:-https://api.example.com}/mcp"))
                .isEmpty();
        assertThat(SecretMask.origin("not a url S3CR3T-FAKE-1")).isEmpty();
        assertThat(SecretMask.origin("mailto:someone")).isEmpty();
    }
}
