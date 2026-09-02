package io.outcomeprobe.profile.adapter.in.web;

import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.foundation.SemanticVersionComparator;
import io.outcomeprobe.profile.ProfileLookup;
import io.outcomeprobe.profile.ProfileVerifier;
import io.outcomeprobe.profile.TargetProfile;
import io.outcomeprobe.profile.TargetProfileSpecMapper;
import io.outcomeprobe.scenario.ArtifactDefinition;
import io.outcomeprobe.scenario.ArtifactEnvelope;
import io.outcomeprobe.scenario.ArtifactImportException;
import io.outcomeprobe.scenario.ArtifactImportService;
import io.outcomeprobe.scenario.ArtifactKind;
import io.outcomeprobe.scenario.ArtifactRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Target Profile CRUD·verify를 제공하는 Control API다.
 *
 * <p>책임: 사용자가 자신의 Target을 등록·수정·삭제·사전 검증하게 한다. Profile은
 * TARGET_PROFILE Artifact로 저장되며, secret 값은 secretRef 이름만 보관한다.</p>
 * <p>핵심 불변식: 응답에 secret 값을 절대 포함하지 않는다. verify는 실제 업무
 * Scenario를 실행하지 않고 연결·안전성만 확인한다(§16.4).</p>
 */
@RestController
@RequestMapping("/api/v1/target-profiles")
public class TargetProfileController {

    private final ArtifactImportService importService;
    private final ArtifactRepository artifactRepository;
    private final ProfileLookup profileLookup;
    private final ProfileVerifier profileVerifier;
    private final TargetProfileSpecMapper profileSpecMapper;

    /**
     * Profile API 구현에 필요한 협업 객체를 주입한다.
     *
     * @param importService Artifact import 서비스
     * @param artifactRepository Artifact 저장소
     * @param profileLookup Profile 조회 포트
     * @param profileVerifier Profile 검증기
     * @param profileSpecMapper 검증된 spec의 typed 실행 모델 변환기
     */
    public TargetProfileController(ArtifactImportService importService,
                                   ArtifactRepository artifactRepository,
                                   ProfileLookup profileLookup,
                                   ProfileVerifier profileVerifier,
                                   TargetProfileSpecMapper profileSpecMapper) {
        this.importService = importService;
        this.artifactRepository = artifactRepository;
        this.profileLookup = profileLookup;
        this.profileVerifier = profileVerifier;
        this.profileSpecMapper = profileSpecMapper;
    }

    /**
     * Target Profile을 YAML로 등록한다.
     *
     * @param yaml TargetProfile YAML 본문
     * @return 201과 등록된 Profile 요약
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody String yaml) {
        validateTargetProfile(yaml, null);
        ArtifactDefinition definition = importService.importArtifact(yaml, false);
        return ResponseEntity.status(HttpStatus.CREATED).body(summary(definition));
    }

    /**
     * 등록된 Target Profile 목록을 조회한다.
     *
     * @return Profile 요약 목록
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        return artifactRepository.findAllActive(ArtifactKind.TARGET_PROFILE).stream()
                .map(this::summary)
                .toList();
    }

    /**
     * 이름으로 최신 버전 Target Profile을 조회한다.
     *
     * @param name Profile 이름
     * @return Profile 요약 또는 404
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String name) {
        return findLatest(name)
                .map(definition -> ResponseEntity.ok(summary(definition)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Target Profile을 새 버전으로 갱신한다.
     *
     * <p>PUT은 새 버전 import로 처리한다. 기존 버전은 과거 Run snapshot을 위해
     * 유지된다.</p>
     *
     * @param name Profile 이름
     * @param yaml 새 버전 TargetProfile YAML 본문
     * @return 200과 갱신된 Profile 요약
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String name, @RequestBody String yaml) {
        validateTargetProfile(yaml, name);
        ArtifactDefinition definition = importService.importArtifact(yaml, false);
        return ResponseEntity.ok(summary(definition));
    }

    /**
     * 이름의 모든 버전 Target Profile을 soft delete한다.
     *
     * <p>과거 Run snapshot은 영향을 받지 않는다.</p>
     *
     * @param name Profile 이름
     * @return 204
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        List<ArtifactDefinition> versions = artifactRepository.findAllActive(ArtifactKind.TARGET_PROFILE)
                .stream()
                .filter(definition -> definition.metadata().name().equals(name))
                .toList();
        for (ArtifactDefinition definition : versions) {
            artifactRepository.softDelete(ArtifactKind.TARGET_PROFILE, name,
                    definition.metadata().version());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Target Profile의 연결·안전성을 사전 검증한다.
     *
     * @param name Profile 이름
     * @return 검증 결과 또는 404
     */
    @PostMapping("/{name}/verify")
    public ResponseEntity<Map<String, Object>> verify(@PathVariable String name) {
        return profileLookup.findByName(name)
                .map(profile -> {
                    ProfileVerifier.Result result = profileVerifier.verify(profile);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", name);
                    body.put("passed", result.passed());
                    body.put("message", result.message());
                    body.put("checks", result.checks());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 이름의 최신 버전 Profile을 찾는다.
     */
    private java.util.Optional<ArtifactDefinition> findLatest(String name) {
        return artifactRepository.findAllActive(ArtifactKind.TARGET_PROFILE).stream()
                .filter(definition -> definition.metadata().name().equals(name))
                .max(Comparator.<ArtifactDefinition, String>comparing(
                        definition -> definition.metadata().version(),
                        SemanticVersionComparator.INSTANCE)
                        .thenComparing(definition -> definition.metadata().version())
                        .thenComparing(definition -> definition.contentHash().hexValue()));
    }

    /**
     * 요청 본문을 저장 없이 typed 검증하고 TargetProfile endpoint의 kind·이름 경계를 확정한다.
     *
     * <p>왜 import 전에 수행하는가: 잘못된 kind 또는 URL과 다른 이름의 Artifact가 저장된 뒤
     * 응답 단계에서 거부되는 부분 성공을 방지한다.</p>
     *
     * @param yaml 검증할 Artifact YAML
     * @param expectedName PUT URL이 요구하는 이름이며, create에서는 {@code null}
     */
    private void validateTargetProfile(String yaml, String expectedName) {
        ArtifactEnvelope envelope = importService.validate(yaml);
        if (envelope.kind() != ArtifactKind.TARGET_PROFILE) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "kind",
                    "kind가 TARGET_PROFILE이어야 합니다.");
        }
        if (expectedName != null && !envelope.metadata().name().equals(expectedName)) {
            throw new ArtifactImportException(ProblemCode.SCENARIO_SCHEMA_INVALID, "metadata.name",
                    "URL의 이름과 YAML의 이름이 다릅니다.");
        }
        profileSpecMapper.toProfile(envelope.metadata().name(), envelope.spec());
    }

    /**
     * Artifact를 API 응답용 요약 map으로 변환한다. secret 값을 포함하지 않는다.
     */
    private Map<String, Object> summary(ArtifactDefinition definition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", definition.metadata().name());
        result.put("displayName", definition.metadata().displayName());
        result.put("version", definition.metadata().version());
        result.put("contentHash", definition.contentHash().hexValue());
        result.put("importedAt", definition.importedAt().toString());
        return result;
    }
}
