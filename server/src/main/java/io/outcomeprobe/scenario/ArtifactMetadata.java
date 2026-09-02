package io.outcomeprobe.scenario;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Artifact 공통 metadata를 표현하는 불변 값 객체다.
 *
 * <p>책임: name·version·표시 이름·label의 형식 계약을 생성 시점에 검증해
 * 잘못된 식별자가 저장소에 들어오지 않게 한다.</p>
 * <p>핵심 불변식: name은 DNS label 스타일(소문자·숫자·하이픈, 63자 이하),
 * version은 SemVer만 허용한다. 동일 kind+name+version은 immutable identity다.</p>
 *
 * @param name Artifact 고유 이름
 * @param displayName 사람이 읽는 이름, 최대 120자
 * @param version SemVer 버전 문자열
 * @param labels 분류용 key/value, 값은 63자 이하
 */
public record ArtifactMetadata(
        String name,
        String displayName,
        String version,
        Map<String, String> labels) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern SEMVER_PATTERN =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z-.]+)?(\\+[0-9A-Za-z-.]+)?$");

    /**
     * metadata 형식을 검증하고 인스턴스를 생성한다.
     *
     * @param name Artifact 이름
     * @param displayName 표시 이름
     * @param version SemVer 문자열
     * @param labels label 맵
     * @throws ArtifactImportException 형식 계약을 위반하면 발생한다
     */
    public ArtifactMetadata {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ArtifactImportException(
                    io.outcomeprobe.foundation.ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "metadata.name",
                    "name은 소문자 영문·숫자·하이픈 조합 63자 이하여야 합니다.");
        }
        if (displayName == null || displayName.length() > 120) {
            throw new ArtifactImportException(
                    io.outcomeprobe.foundation.ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "metadata.displayName",
                    "displayName은 1자 이상 120자 이하여야 합니다.");
        }
        if (version == null || !SEMVER_PATTERN.matcher(version).matches()) {
            throw new ArtifactImportException(
                    io.outcomeprobe.foundation.ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "metadata.version",
                    "version은 SemVer 형식이어야 합니다.");
        }
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
