package io.outcomeprobe.profile.adapter.out.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.outcomeprobe.profile.SecretResolutionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 환경변수·mounted file SecretResolver의 우선순위와 보안 경계를 검증한다. */
class EnvironmentFileSecretResolverTest {

    @TempDir
    Path secretDirectory;

    /** 정식 환경변수는 같은 ref의 mounted file보다 우선해야 한다. */
    @Test
    void canonicalEnvironmentOverridesMountedFile() throws Exception {
        Files.writeString(secretDirectory.resolve("target-api-token"), "file-value");
        Map<String, String> environment = Map.of(
                "OUTCOMEPROBE_SECRET_TARGET_API_TOKEN", "environment-value");
        EnvironmentFileSecretResolver resolver = resolver(environment);

        assertThat(resolver.resolveRequired("target-api-token")).isEqualTo("environment-value");
    }

    /** 환경변수가 없으면 mounted file의 마지막 CRLF 한 번만 제거해 사용해야 한다. */
    @Test
    void readsMountedFileAndRemovesSingleTrailingLineEnding() throws Exception {
        Files.writeString(secretDirectory.resolve("target-api-token"), "Bearer file-value\r\n");

        assertThat(resolver(Map.of()).resolveRequired("target-api-token"))
                .isEqualTo("Bearer file-value");
    }

    /** legacy demo 환경변수는 callback-signing-key에만 compatibility fallback으로 허용한다. */
    @Test
    void compatibilityAliasIsRestrictedToCallbackSigningKey() {
        EnvironmentFileSecretResolver resolver = resolver(Map.of(
                "OUTCOMEPROBE_DEMO_CALLBACK_SECRET", "compatibility-value"));

        assertThat(resolver.resolveRequired("callback-signing-key"))
                .isEqualTo("compatibility-value");
        assertThat(resolver.resolve("other-signing-key")).isEmpty();
    }

    /** 아무 설정이 없을 때 hardcoded demo fallback 없이 empty를 반환해야 한다. */
    @Test
    void missingSecretDoesNotUseHardcodedFallback() {
        EnvironmentFileSecretResolver resolver = resolver(Map.of());

        assertThat(resolver.resolve("callback-signing-key")).isEmpty();
        assertThatThrownBy(() -> resolver.resolveRequired("callback-signing-key"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageNotContaining("local-demo-secret");
    }

    /** traversal·separator가 포함된 ref는 directory 조회 전에 거부해야 한다. */
    @Test
    void rejectsTraversalReference() {
        assertThatThrownBy(() -> resolver(Map.of()).resolve("../outside"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageNotContaining("../outside");
    }

    /** 환경변수 이름이 충돌하는 underscore·대문자 ref는 canonical kebab-case로 강제한다. */
    @Test
    void rejectsNonCanonicalReferenceThatWouldCollideInEnvironment() {
        EnvironmentFileSecretResolver resolver = resolver(Map.of(
                "OUTCOMEPROBE_SECRET_TARGET_API_TOKEN", "canonical-value"));

        assertThat(resolver.resolveRequired("target-api-token")).isEqualTo("canonical-value");
        assertThatThrownBy(() -> resolver.resolve("target_api_token"))
                .isInstanceOf(SecretResolutionException.class);
        assertThatThrownBy(() -> resolver.resolve("TARGET-API-TOKEN"))
                .isInstanceOf(SecretResolutionException.class);
    }

    /** 16KiB를 넘는 mounted secret은 읽어 요청에 넣지 않아야 한다. */
    @Test
    void rejectsOversizedMountedFile() throws Exception {
        Files.write(secretDirectory.resolve("large-secret"), new byte[16 * 1024 + 1]);

        assertThatThrownBy(() -> resolver(Map.of()).resolve("large-secret"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("large-secret");
    }

    /** file과 환경변수의 CR/LF는 HTTP header injection 가능성이 있어 거부해야 한다. */
    @Test
    void rejectsCrlfWithoutLeakingValue() throws Exception {
        String unsafeValue = "Bearer safe\r\nX-Injected: exposed-value";
        Files.writeString(secretDirectory.resolve("file-secret"), unsafeValue);
        Map<String, String> environment = new HashMap<>();
        environment.put("OUTCOMEPROBE_SECRET_ENV_SECRET", unsafeValue);

        assertThatThrownBy(() -> resolver(Map.of()).resolve("file-secret"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageNotContaining("exposed-value");
        assertThatThrownBy(() -> resolver(environment).resolve("env-secret"))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageNotContaining("exposed-value");
    }

    /** 테스트 환경 map과 임시 mounted directory로 resolver를 만든다. */
    private EnvironmentFileSecretResolver resolver(Map<String, String> environment) {
        return new EnvironmentFileSecretResolver(environment::get, secretDirectory.toString());
    }
}
