package io.outcomeprobe.scenario.adapter.in.web;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactEnvelope;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactImportService;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Artifact validate·import·조회를 제공하는 Control API다.
 *
 * <p>책임: 사용자가 YAML 문서를 검증하고 import하며 저장된 Artifact를 조회하는
 * 진입점을 제공한다. 업무 판단은 애플리케이션 서비스에 위임한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 Controller는 secret 값을 응답에 포함하지
 * 않으며 persistence entity를 직접 반환하지 않는다.</p>
 */
@RestController
@RequestMapping("/api/v1/artifacts")
public class ArtifactController {

    private static final List<ArtifactKind> PUBLIC_KINDS = List.of(
            ArtifactKind.PROVIDER_CONTRACT,
            ArtifactKind.SCENARIO,
            ArtifactKind.TARGET_PROFILE);

    private final ArtifactImportService importService;
    private final ArtifactRepository artifactRepository;

    /**
     * API 구현에 필요한 협업 객체를 주입한다.
     *
     * @param importService 검증·import 정책 서비스
     * @param artifactRepository 조회용 저장소
     */
    public ArtifactController(ArtifactImportService importService, ArtifactRepository artifactRepository) {
        this.importService = importService;
        this.artifactRepository = artifactRepository;
    }

    /**
     * 원본 YAML을 저장 없이 검증하고 결과를 반환한다.
     *
     * @param yaml 원본 YAML 본문
     * @return 검증 결과 요약
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody String yaml) {
        ArtifactEnvelope envelope = importService.validate(yaml);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("kind", envelope.kind().name());
        result.put("name", envelope.metadata().name());
        result.put("version", envelope.metadata().version());
        result.put("contentHash", envelope.contentHash().hexValue());
        return result;
    }

    /**
     * 원본 YAML을 import하고 생성·재사용된 Artifact 요약을 반환한다.
     *
     * @param yaml 원본 YAML 본문
     * @return import 결과 요약과 201 상태
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importArtifact(@RequestBody String yaml) {
        ArtifactDefinition definition = importService.importArtifact(yaml, false);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary(definition));
    }

    /**
     * kind 필터로 활성 Artifact 목록을 조회한다.
     *
     * @param kind 조회할 Artifact 종류, 없으면 전체
     * @return Artifact 요약 목록
     */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String kind) {
        List<ArtifactDefinition> definitions = kind == null
                ? PUBLIC_KINDS.stream()
                        .flatMap(each -> artifactRepository.findAllActive(each).stream())
                        .toList()
                : artifactRepository.findAllActive(publicKind(kind));
        return definitions.stream().map(this::summary).toList();
    }

    /**
     * 특정 identity의 Artifact를 조회한다.
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return Artifact 요약 또는 404
     */
    @GetMapping("/{kind}/{name}/{version}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String kind,
            @PathVariable String name,
            @PathVariable String version) {
        return artifactRepository.findByIdentity(publicKind(kind), name, version)
                .map(definition -> ResponseEntity.ok(summary(definition)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Artifact의 원본 YAML을 조회한다.
     *
     * <p>UI가 Scenario 정의를 읽기 전용으로 보여주려 사용할 뿐, 이 응답으로
     * 정의를 수정할 수 없다. 수정은 새 version import로만 가능하다.</p>
     *
     * @param kind Artifact 종류
     * @param name Artifact 이름
     * @param version SemVer 버전
     * @return 원본 YAML 또는 404
     */
    @GetMapping(value = "/{kind}/{name}/{version}/raw", produces = "text/yaml")
    public ResponseEntity<String> getRaw(
            @PathVariable String kind,
            @PathVariable String name,
            @PathVariable String version) {
        return artifactRepository.findByIdentity(publicKind(kind), name, version)
                .map(definition -> ResponseEntity.ok(definition.rawYaml()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 외부 조회 API가 허용하는 세 Artifact 종류만 해석한다.
     *
     * @param rawKind URL 또는 query에 포함된 종류 문자열
     * @return 공개 조회가 허용된 Artifact 종류
     */
    private ArtifactKind publicKind(String rawKind) {
        ArtifactKind parsed;
        try {
            parsed = ArtifactKind.valueOf(rawKind);
        } catch (IllegalArgumentException e) {
            throw unsupportedKind(rawKind);
        }
        if (!PUBLIC_KINDS.contains(parsed)) {
            throw unsupportedKind(rawKind);
        }
        return parsed;
    }

    /**
     * 지원하지 않는 종류에 대한 기계 판독 가능한 입력 예외를 만든다.
     *
     * @param rawKind 거부할 종류 문자열
     * @return kind 필드에 결속된 import 예외
     */
    private ArtifactImportException unsupportedKind(String rawKind) {
        return new ArtifactImportException(
                ProblemCode.ARTIFACT_KIND_NOT_SUPPORTED,
                "kind",
                "core-only 배포판에서 지원하지 않는 Artifact kind입니다: " + rawKind);
    }

    /**
     * Artifact를 API 응답용 요약 map으로 변환한다.
     */
    private Map<String, Object> summary(ArtifactDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", definition.kind().name());
        result.put("name", definition.metadata().name());
        result.put("displayName", definition.metadata().displayName());
        result.put("version", definition.metadata().version());
        result.put("contentHash", definition.contentHash().hexValue());
        result.put("builtIn", definition.builtIn());
        result.put("importedAt", definition.importedAt().toString());
        return result;
    }
}
