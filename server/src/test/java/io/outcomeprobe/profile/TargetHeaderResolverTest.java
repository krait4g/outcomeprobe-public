package io.outcomeprobe.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.outcomeprobe.foundation.HashValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Target 요청 header의 해석·우선순위·비저장 불변식을 검증한다. */
class TargetHeaderResolverTest {

    /** default→explicit→secret→protected 순서와 case-insensitive 교체를 검증한다. */
    @Test
    void mergesAllLayersCaseInsensitivelyWithProtectedLast() {
        Map<String, String> defaultHeaders = new LinkedHashMap<>();
        defaultHeaders.put("X-Tenant", "default-tenant");
        defaultHeaders.put("X-Layer", "default-layer");
        defaultHeaders.put("X-OutcomeProbe-Run-Id", "profile-spoof");
        Map<String, String> secretHeaders = new LinkedHashMap<>();
        secretHeaders.put("x-layer", "layer-ref");
        secretHeaders.put("Authorization", "authorization-ref");
        secretHeaders.put("x-outcomeprobe-run-id", "spoof-ref");
        TargetProfile profile = profile(defaultHeaders, secretHeaders);
        Map<String, String> values = Map.of(
                "layer-ref", "secret-layer",
                "authorization-ref", "Bearer resolved-value",
                "spoof-ref", "secret-spoof");
        TargetHeaderResolver resolver = new TargetHeaderResolver(
                secretRef -> Optional.ofNullable(values.get(secretRef)));

        Map<String, String> headers = resolver.resolve(profile,
                Map.of("X-LAYER", "explicit-layer", "X-Explicit", "explicit-value"),
                Map.of("X-OutcomeProbe-Run-Id", "actual-run", "x-layer", "protected-layer"));

        assertThat(valueIgnoringCase(headers, "x-tenant")).isEqualTo("default-tenant");
        assertThat(valueIgnoringCase(headers, "authorization")).isEqualTo("Bearer resolved-value");
        assertThat(valueIgnoringCase(headers, "x-layer")).isEqualTo("protected-layer");
        assertThat(valueIgnoringCase(headers, "x-outcomeprobe-run-id")).isEqualTo("actual-run");
        assertThat(headers.keySet().stream()
                .filter(name -> name.equalsIgnoreCase("x-layer"))).hasSize(1);
        assertThat(headers.keySet().stream()
                .filter(name -> name.equalsIgnoreCase("x-outcomeprobe-run-id"))).hasSize(1);
    }

    /** 필수 Profile secret 누락은 실제 값 없이 명시적 예외가 돼야 한다. */
    @Test
    void missingSecretFailsWithoutLeakingAnyValue() {
        TargetHeaderResolver resolver = new TargetHeaderResolver(secretRef -> Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(
                profile(Map.of(), Map.of("Authorization", "missing-token")), Map.of()))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("missing-token")
                .hasMessageNotContaining("Bearer");
    }

    /** custom resolver가 CR/LF 값을 반환해도 공용 병합 경계에서 다시 거부해야 한다. */
    @Test
    void rejectsUnsafeResolvedValueWithoutEchoingIt() {
        String unsafe = "safe\r\nX-Leak: actual-secret";
        TargetHeaderResolver resolver = new TargetHeaderResolver(
                secretRef -> Optional.of(unsafe));

        assertThatThrownBy(() -> resolver.resolve(
                profile(Map.of(), Map.of("Authorization", "auth-ref")), Map.of()))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("auth-ref")
                .hasMessageNotContaining("actual-secret");
    }

    /** 명시/default header의 잘못된 이름과 CR/LF 값도 HTTP 호출 전에 거부해야 한다. */
    @Test
    void rejectsInvalidExplicitHeaderNameAndValue() {
        TargetHeaderResolver resolver = new TargetHeaderResolver(secretRef -> Optional.empty());
        TargetProfile profile = profile(Map.of(), Map.of());

        assertThatThrownBy(() -> resolver.resolve(profile, Map.of("Bad\r\nName", "value")))
                .isInstanceOf(TargetHeaderResolutionException.class);
        assertThatThrownBy(() -> resolver.resolve(profile, Map.of("X-Safe", "value\nInjected")))
                .isInstanceOf(TargetHeaderResolutionException.class)
                .hasMessageNotContaining("Injected");
    }

    /** JDK 제한 header와 CR/LF 외 제어문자도 adapter 진입 전에 거부해야 한다. */
    @Test
    void rejectsJdkRestrictedHeaderAndOtherControlCharacters() {
        TargetHeaderResolver resolver = new TargetHeaderResolver(secretRef -> Optional.empty());
        TargetProfile profile = profile(Map.of(), Map.of());

        assertThatThrownBy(() -> resolver.resolve(profile, Map.of("Content-Length", "1")))
                .isInstanceOf(TargetHeaderResolutionException.class)
                .hasMessageContaining("제한 header");
        assertThatThrownBy(() -> resolver.resolve(profile, Map.of("X-Control", "safe\u0000unsafe")))
                .isInstanceOf(TargetHeaderResolutionException.class)
                .hasMessageNotContaining("unsafe");
    }

    /** 한 layer 안의 대소문자 중복은 Map 순서에 따라 값이 바뀌므로 거부해야 한다. */
    @Test
    void rejectsCaseInsensitiveDuplicateWithinOneLayer() {
        Map<String, String> ambiguous = new LinkedHashMap<>();
        ambiguous.put("X-Tenant", "first");
        ambiguous.put("x-tenant", "second");
        TargetHeaderResolver resolver = new TargetHeaderResolver(secretRef -> Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(profile(ambiguous, Map.of()), Map.of()))
                .isInstanceOf(TargetHeaderResolutionException.class)
                .hasMessageContaining("중복 header");
    }

    /** 해석된 값은 Profile snapshot JSON에 들어가지 않고 secretRef만 남아야 한다. */
    @Test
    void resolvedValueIsNotPersistedInProfileSnapshot() throws Exception {
        String resolvedSecret = "Bearer runtime-only-value";
        TargetProfile profile = profile(Map.of(), Map.of("Authorization", "auth-ref"));
        TargetHeaderResolver resolver = new TargetHeaderResolver(
                secretRef -> Optional.of(resolvedSecret));
        assertThat(valueIgnoringCase(resolver.resolve(profile, Map.of()), "Authorization"))
                .isEqualTo(resolvedSecret);

        ObjectMapper mapper = new ObjectMapper();
        String sourceHash = HashValue.sha256("profile-yaml").hexValue();
        String canonicalHash = TargetProfileSafeSnapshot.calculateCanonicalHash(mapper,
                "outcomeprobe.io/v1alpha1", profile.name(), "1.0.0", sourceHash, profile);
        TargetProfileSafeSnapshot snapshot = new TargetProfileSafeSnapshot(
                "outcomeprobe.io/v1alpha1", profile.name(), "1.0.0", sourceHash,
                canonicalHash, profile);
        String serialized = mapper.writeValueAsString(snapshot);

        assertThat(serialized).contains("auth-ref").doesNotContain(resolvedSecret);
    }

    /** 테스트용 Profile을 만든다. */
    private TargetProfile profile(Map<String, String> defaultHeaders,
                                  Map<String, String> secretHeaders) {
        return new TargetProfile("target", "http://localhost:8091",
                List.of("/**"), List.of("GET", "POST"), List.of("localhost"), List.of(8091),
                defaultHeaders, secretHeaders, Map.of(), List.of(), List.of());
    }

    /** map에서 HTTP 대소문자를 무시하고 값을 찾는다. */
    private String valueIgnoringCase(Map<String, String> headers, String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
