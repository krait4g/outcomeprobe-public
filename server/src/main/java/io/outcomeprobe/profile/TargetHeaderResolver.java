package io.outcomeprobe.profile;

import io.outcomeprobe.foundation.ProblemCode;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Target 요청 header를 Profile과 Step 입력으로부터 실행 직전에 완성한다.
 *
 * <p>병합 우선순위는 Profile default → Step·Worker 명시 header → 해석된 secret header
 * → Callback 보호 header다. 모든 key는 대소문자를 무시해 교체하므로 같은 HTTP header가
 * 다른 casing으로 중복 전송되지 않는다.</p>
 */
@Service
public class TargetHeaderResolver {

    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESTRICTED_HEADER_NAMES = Set.of(
            "connection", "content-length", "expect", "host", "upgrade");
    private static final Set<String> SENSITIVE_LITERAL_HEADERS = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie");

    private final SecretResolver secretResolver;

    /**
     * secret 해석 포트를 주입한다.
     *
     * @param secretResolver 환경변수·mounted file secret 해석 포트
     */
    public TargetHeaderResolver(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * 일반 Target·Probe 요청 header를 완성한다.
     *
     * @param profile Run에 고정된 Target Profile
     * @param explicitHeaders Step이 명시한 안전 header
     * @return case-insensitive 중복이 제거된 immutable header map
     */
    public Map<String, String> resolve(TargetProfile profile,
                                       Map<String, String> explicitHeaders) {
        return resolve(profile, explicitHeaders, Map.of());
    }

    /**
     * Callback 보호 header까지 마지막에 적용해 Profile이 상관관계·서명 값을 위조하지 못하게 한다.
     *
     * @param profile Run에 고정된 Target Profile
     * @param explicitHeaders Worker가 명시한 일반 header
     * @param protectedHeaders 마지막에 강제할 시스템·서명 header
     * @return case-insensitive 중복이 제거된 immutable header map
     */
    public Map<String, String> resolve(TargetProfile profile,
                                       Map<String, String> explicitHeaders,
                                       Map<String, String> protectedHeaders) {
        if (profile == null) {
            throw new TargetHeaderResolutionException(ProblemCode.ARTIFACT_SNAPSHOT_CORRUPTED,
                    "Target Profile snapshot이 없습니다.");
        }
        LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        putLayer(resolved, profile.defaultHeaders(), "Profile default header", false);
        putLayer(resolved, explicitHeaders, "명시 요청 header", false);
        validateUniqueNames(profile.secretHeaders(), "Profile secret header");
        profile.secretHeaders().forEach((name, secretRef) -> {
            validateName(name, "Profile secret header");
            String value = secretResolver.resolveRequired(secretRef);
            if (!isSafeValue(value)) {
                throw SecretResolutionException.invalidValue(secretRef);
            }
            putCaseInsensitive(resolved, name, value);
        });
        putLayer(resolved, protectedHeaders, "Callback 보호 header", true);
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    /** 한 우선순위 layer를 검증하고 case-insensitive 방식으로 적용한다. */
    private void putLayer(Map<String, String> target, Map<String, String> layer, String source,
                          boolean sensitiveLiteralAllowed) {
        if (layer == null) {
            return;
        }
        validateUniqueNames(layer, source);
        layer.forEach((name, value) -> {
            validateName(name, source);
            if (!sensitiveLiteralAllowed && isSensitiveLiteralHeader(name)) {
                throw new TargetHeaderResolutionException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        source + "에는 민감 header literal을 사용할 수 없습니다: " + safeName(name));
            }
            if (!isSafeValue(value)) {
                throw new TargetHeaderResolutionException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        source + " 값에 제어문자 또는 null을 사용할 수 없습니다: " + safeName(name));
            }
            putCaseInsensitive(target, name, value);
        });
    }

    /** RFC HTTP field-name token이 아니거나 CR/LF를 포함한 이름을 거부한다. */
    private void validateName(String name, String source) {
        if (name == null || !HEADER_NAME.matcher(name).matches()) {
            throw new TargetHeaderResolutionException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    source + " 이름이 올바르지 않습니다: " + safeName(name));
        }
        if (RESTRICTED_HEADER_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new TargetHeaderResolutionException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                    source + "에 JDK 제한 header를 사용할 수 없습니다: " + safeName(name));
        }
    }

    /** 값이 존재하고 HTTP field-value에 허용되지 않는 제어문자가 없는지 반환한다. */
    private boolean isSafeValue(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 0x20 && character != '\t') || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** 한 layer에 대소문자만 다른 중복 header가 있으면 순서 의존 전송을 막는다. */
    private void validateUniqueNames(Map<String, String> headers, String source) {
        Set<String> normalizedNames = new HashSet<>();
        headers.keySet().forEach(name -> {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if (!normalizedNames.add(normalized)) {
                throw new TargetHeaderResolutionException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        source + "에 대소문자 중복 header가 있습니다: " + safeName(name));
            }
        });
    }

    /** 실제 값 대신 secretRef를 사용해야 하는 민감 header 이름인지 판정한다. */
    private boolean isSensitiveLiteralHeader(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
        return SENSITIVE_LITERAL_HEADERS.contains(normalized)
                || normalized.contains("apikey") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("password");
    }

    /** 기존 casing의 같은 header를 제거하고 현재 layer 값을 넣는다. */
    private void putCaseInsensitive(Map<String, String> target, String name, String value) {
        String normalized = name.toLowerCase(Locale.ROOT);
        target.keySet().removeIf(existing -> existing.toLowerCase(Locale.ROOT).equals(normalized));
        target.put(name, value);
    }

    /** 오류 메시지에 값이 아닌 제한된 header 이름만 포함한다. */
    private String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "missing-header";
        }
        String sanitized = name.replaceAll("[^!#$%&'*+.^_`|~0-9A-Za-z-]", "?");
        return sanitized.substring(0, Math.min(sanitized.length(), 128));
    }
}
