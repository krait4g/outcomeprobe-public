package io.outcomeprobe.scenario;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.profile.TargetProfileUriPolicy;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import tools.jackson.databind.JsonNode;

/**
 * Artifact와 Run snapshot에 민감 credential 원문이 들어오지 못하게 하는 공용 정책이다.
 *
 * <p>{@code headers}·{@code defaultHeaders} 계열 객체의 key를 대소문자와 구분 기호에
 * 무관하게 검사한다. 민감 값은 literal로 저장할 수 없으며, TargetProfile의
 * {@code secretHeaders: { Header: { secretRef: name } }} 구조만 허용한다.</p>
 */
public final class ArtifactSecretPolicy {

    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie");
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password", "passwd", "apikey", "token", "accesstoken", "refreshtoken",
            "clientsecret", "privatekey", "authorization", "cookie", "setcookie", "secret");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern SAFE_SECRET_REFERENCE = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final Pattern ENV_REFERENCE = Pattern.compile("^\\$\\{[^}]+}$");
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern COMMENT_ASSIGNMENT =
            Pattern.compile("([A-Za-z0-9_-]+)\\s*[:=]\\s*(\\S.*)");

    /** 인스턴스 생성을 막는다. */
    private ArtifactSecretPolicy() {
    }

    /**
     * Artifact spec 전체를 재귀 순회해 민감 header literal을 거부한다.
     *
     * @param spec import 또는 snapshot 대상 spec
     * @throws ArtifactImportException 민감 header literal 또는 잘못된 secretRef 구조가 있으면 발생한다
     */
    public static void validateSafeSpec(JsonNode spec) {
        if (spec != null) {
            scan(spec, "spec");
        }
    }

    /**
     * metadata를 포함한 Artifact 문서 전체에서 민감 credential literal을 거부한다.
     *
     * @param document YAML을 해석한 최상위 JSON tree
     * @throws ArtifactImportException 민감 literal 또는 잘못된 secretRef 구조가 있으면 발생한다
     */
    public static void validateSafeDocument(JsonNode document) {
        if (document != null) {
            scan(document, "artifact");
        }
    }

    /**
     * Template 치환이 끝난 실행 입력에 Artifact와 같은 secret·URI 안전 정책을 적용한다.
     *
     * <p>Import 시 안전했던 상대 path와 일반 field도 Run variable 치환 뒤에는
     * credential field, absolute URL, 민감 query 또는 fragment로 바뀔 수 있다.
     * 저장 직전 이 경계를 다시 통과시켜 compiled plan과 Evidence 유출을 차단한다.</p>
     *
     * @param resolvedInput 치환이 끝난 변수 또는 Step JSON tree
     * @param path 오류에 사용할 논리적 입력 경로
     * @throws ArtifactImportException 치환 결과가 Artifact 안전 정책을 위반하면 발생한다
     */
    public static void validateResolvedInput(JsonNode resolvedInput, String path) {
        if (resolvedInput != null) {
            scan(resolvedInput, path);
        }
    }

    /**
     * YAML comment에 credential assignment가 적혀 원본 Artifact 저장소에 남는 것을 거부한다.
     *
     * <p>일반 설명 comment는 허용하지만 {@code password: value}처럼 민감 key와 값을
     * 함께 쓴 comment는 semantic tree에서 사라지므로 parse 전에 별도로 검사한다.</p>
     *
     * @param rawYaml import 또는 snapshot 대상 원문 YAML
     * @throws ArtifactImportException 민감 comment assignment가 있으면 발생한다
     */
    public static void validateRawYamlText(String rawYaml) {
        if (rawYaml == null) {
            return;
        }
        for (String line : rawYaml.split("\\R", -1)) {
            int commentStart = line.indexOf('#');
            if (commentStart < 0) {
                continue;
            }
            Matcher matcher = COMMENT_ASSIGNMENT.matcher(line.substring(commentStart + 1));
            while (matcher.find()) {
                String normalizedName = normalize(matcher.group(1));
                if (!normalizedName.equals("secretref") && isSensitiveFieldName(normalizedName)) {
                    throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                            "artifact.comment",
                            "YAML comment에 credential 값을 저장할 수 없습니다.");
                }
            }
        }
    }

    /**
     * 타입 모델로 변환된 TargetProfile의 default header도 같은 정책으로 검사한다.
     *
     * @param defaultHeaders Profile default header map
     * @throws ArtifactImportException 민감 header literal이 있으면 발생한다
     */
    public static void validateSafeDefaultHeaders(Map<String, String> defaultHeaders) {
        if (defaultHeaders == null) {
            return;
        }
        validateHeaderNames(defaultHeaders.keySet(), "spec.defaultHeaders");
        defaultHeaders.keySet().forEach(name ->
                rejectSensitiveLiteral(name, "spec.defaultHeaders." + name));
    }

    /** JSON tree를 순회하며 header container를 찾는다. */
    private static void scan(JsonNode node, String path) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String childPath = path + "." + entry.getKey();
                String container = normalize(entry.getKey());
                rejectSensitiveFieldValue(container, entry.getValue(), childPath);
                if (entry.getValue().isObject() && container.equals("secretheaders")) {
                    validateSecretReferences(entry.getValue(), childPath);
                } else if (entry.getValue().isObject() && container.endsWith("headers")) {
                    Set<String> names = new java.util.LinkedHashSet<>();
                    entry.getValue().properties().forEach(header -> names.add(header.getKey()));
                    validateHeaderNames(names, childPath);
                    entry.getValue().properties().forEach(header ->
                            rejectSensitiveLiteral(header.getKey(), childPath + "." + header.getKey()));
                }
                scan(entry.getValue(), childPath);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                scan(node.get(i), path + "[" + i + "]");
            }
        }
    }

    /** header 밖의 credential 필드도 literal 대신 secretRef만 저장하도록 강제한다. */
    private static void rejectSensitiveFieldValue(String normalizedName, JsonNode value, String path) {
        if (normalizedName.equals("path") && value != null && value.isTextual()) {
            validateRequestPath(value.asText(), path);
        }
        if (normalizedName.equals("baseuri")
                && (value == null || !value.isTextual()
                        || !TargetProfileUriPolicy.isSafe(value.asText()))) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "baseUri는 credential·query·fragment 없는 absolute HTTP(S) URI여야 합니다.");
        }
        if (normalizedName.equals("secretref")) {
            if (value != null && value.isTextual()
                    && SAFE_SECRET_REFERENCE.matcher(value.asText()).matches()) {
                return;
            }
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "secretRef는 소문자 kebab-case 참조 이름이어야 합니다.");
        }
        if (isSecretReferenceContainer(normalizedName) || !isSensitiveFieldName(normalizedName)
                || value == null || value.isNull()
                || value.isTextual() && (value.asText().isBlank()
                        || ENV_REFERENCE.matcher(value.asText()).matches())) {
            return;
        }
        if (value.isObject() && value.size() == 1 && value.path("secretRef").isTextual()
                && SAFE_SECRET_REFERENCE.matcher(value.path("secretRef").asText()).matches()) {
            return;
        }
        throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                "민감 credential literal은 Artifact에 저장할 수 없습니다. secretRef를 사용하세요.");
    }

    /** request path의 fragment와 민감 query credential literal을 거부한다. */
    private static void validateRequestPath(String value, String path) {
        if (ABSOLUTE_URL.matcher(value).find()) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "HTTP request path에는 absolute URL을 사용할 수 없습니다.");
        }
        if (value.indexOf('#') >= 0) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "HTTP request path에는 fragment를 사용할 수 없습니다.");
        }
        int queryStart = value.indexOf('?');
        if (queryStart < 0 || queryStart == value.length() - 1) {
            return;
        }
        String query = value.substring(queryStart + 1);
        for (String parameter : query.split("[&;]", -1)) {
            String rawName = parameter.split("=", 2)[0];
            String name;
            try {
                name = java.net.URLDecoder.decode(rawName, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                        "HTTP query parameter 인코딩이 올바르지 않습니다.");
            }
            if (isSensitiveFieldName(normalize(name))) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                        "민감 query credential은 path에 저장할 수 없습니다. secret header를 사용하세요.");
            }
        }
    }

    /** secret 값이 아니라 참조 구조를 나타내는 예약 field인지 판정한다. */
    private static boolean isSecretReferenceContainer(String normalizedName) {
        return normalizedName.equals("secretheaders");
    }

    /** credential 의미가 있는 복합 field 이름까지 보수적으로 판정한다. */
    private static boolean isSensitiveFieldName(String normalizedName) {
        return SENSITIVE_FIELD_NAMES.contains(normalizedName)
                || normalizedName.contains("password")
                || normalizedName.contains("passwd")
                || normalizedName.contains("secret")
                || normalizedName.contains("token")
                || normalizedName.contains("apikey")
                || normalizedName.contains("accesskey")
                || normalizedName.contains("privatekey")
                || normalizedName.contains("signingkey")
                || normalizedName.contains("credential")
                || normalizedName.contains("bearer");
    }

    /** secretHeaders의 각 값이 secretRef 객체인지 확인한다. */
    private static void validateSecretReferences(JsonNode headers, String path) {
        Set<String> names = new java.util.LinkedHashSet<>();
        headers.properties().forEach(entry -> names.add(entry.getKey()));
        validateHeaderNames(names, path);
        headers.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isObject() || value.size() != 1 || !value.path("secretRef").isTextual()
                    || !SAFE_SECRET_REFERENCE.matcher(value.path("secretRef").asText()).matches()) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        path + "." + entry.getKey(),
                        "secretHeaders 값은 소문자 kebab-case secretRef 이름만 가진 객체여야 합니다.");
            }
        });
    }

    /** header token 형식과 대소문자 중복을 import 시점에 거부한다. */
    private static void validateHeaderNames(Set<String> names, String path) {
        Set<String> normalizedNames = new HashSet<>();
        names.forEach(name -> {
            if (name == null || !HEADER_NAME.matcher(name).matches()) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        path, "HTTP header 이름이 올바르지 않습니다.");
            }
            if (!normalizedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID,
                        path, "대소문자만 다른 중복 HTTP header를 사용할 수 없습니다.");
            }
        });
    }

    /** 민감 header 이름이면 literal 저장 오류를 발생시킨다. */
    private static void rejectSensitiveLiteral(String headerName, String path) {
        String normalized = normalize(headerName);
        if (SENSITIVE_HEADER_NAMES.contains(normalized)
                || normalized.contains("apikey")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, path,
                    "민감 header는 literal로 저장할 수 없습니다. secretHeaders.secretRef 구조를 사용하세요.");
        }
    }

    /** header와 container 이름 비교를 위해 대소문자와 구분 기호를 제거한다. */
    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
    }
}
