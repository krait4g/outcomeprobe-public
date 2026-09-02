package io.outcomeprobe.execution;

import io.outcomeprobe.foundation.CanonicalJson;
import io.outcomeprobe.foundation.ClockProvider;
import io.outcomeprobe.foundation.HashValue;
import io.outcomeprobe.foundation.ProblemCode;
import io.outcomeprobe.scenario.ArtifactImportException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Control API Run 변경 요청을 idempotency key와 하나의 durable run에 결속한다.
 *
 * <p>책임: 안전한 key 형식을 검증하고 원문 대신 hash만 저장하며, 같은 의도의 응답 유실
 * 재시도는 기존 run 식별자를 반환한다. 같은 key를 다른 요청에 재사용하면 409 경계로 거부한다.</p>
 * <p>핵심 불변식: ledger 선점, run 저장, ledger 결속은 하나의 PostgreSQL 트랜잭션이다.</p>
 */
@Service
public class RunRequestIdempotencyService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._~+/-]+={0,2}$");
    private static final int MINIMUM_KEY_LENGTH = 16;
    private static final int MAXIMUM_KEY_LENGTH = 128;

    private final RunEngine runEngine;
    private final RunRequestIdempotencyPort repository;
    private final ClockProvider clockProvider;
    private final ObjectMapper objectMapper;

    /**
     * Run engine과 durable ledger를 결속한다.
     *
     * @param runEngine 실제 Run 생성기
     * @param repository idempotency ledger
     * @param clockProvider 최초 접수 시각 공급자
     * @param objectMapper canonical 요청 변환기
     */
    public RunRequestIdempotencyService(
            RunEngine runEngine,
            RunRequestIdempotencyPort repository,
            ClockProvider clockProvider,
            ObjectMapper objectMapper) {
        this.runEngine = runEngine;
        this.repository = repository;
        this.clockProvider = clockProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Run 생성 의도를 최초 run 또는 기존 run 식별자로 수렴시킨다.
     *
     * @param request Run 생성 요청
     * @param idempotencyKey 운영 client가 의도마다 만든 key
     * @return 최초 생성되었거나 이미 결속된 run 식별자
     */
    @Transactional
    public String createRun(CreateRunRequest request, String idempotencyKey) {
        return execute("CREATE", idempotencyKey,
                CanonicalJson.sha256(objectMapper, request), () -> runEngine.createRun(request));
    }

    /**
     * Run 재실행 의도를 최초 run 또는 기존 run 식별자로 수렴시킨다.
     *
     * @param sourceRunId 재실행할 원본 run 식별자
     * @param idempotencyKey 운영 client가 의도마다 만든 key
     * @return 최초 생성되었거나 이미 결속된 run 식별자
     */
    @Transactional
    public String rerun(String sourceRunId, String idempotencyKey) {
        String requestHash = CanonicalJson.sha256(objectMapper, Map.of("sourceRunId", sourceRunId));
        return execute("RERUN", idempotencyKey, requestHash, () -> runEngine.rerun(sourceRunId));
    }

    /** 같은 key 행을 잠근 뒤 요청 동일성에 따라 기존 결과를 재사용하거나 새 run을 만든다. */
    private String execute(String operation, String rawKey, String requestHash, RunCreator creator) {
        String keyHash = HashValue.sha256(validateKey(rawKey)).hexValue();
        repository.reserve(operation, keyHash, requestHash, clockProvider.now());
        RunRequestIdempotencyPort.Entry entry = repository.lock(operation, keyHash);
        if (!requestHash.equals(entry.requestHash())) {
            throw new ArtifactImportException(
                    ProblemCode.RUN_IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key",
                    "Idempotency-Key가 다른 요청에 이미 사용되었습니다.");
        }
        if (entry.runId() != null) {
            return entry.runId();
        }
        String runId = creator.create();
        repository.bindRun(operation, keyHash, runId);
        return runId;
    }

    /** header injection과 비정상 저장소 키를 차단하는 제한된 token68 계약을 적용한다. */
    private String validateKey(String idempotencyKey) {
        if (idempotencyKey == null
                || idempotencyKey.length() < MINIMUM_KEY_LENGTH
                || idempotencyKey.length() > MAXIMUM_KEY_LENGTH
                || !SAFE_KEY.matcher(idempotencyKey).matches()) {
            throw new ArtifactImportException(
                    ProblemCode.SCENARIO_SCHEMA_INVALID,
                    "Idempotency-Key",
                    "Idempotency-Key 형식이 올바르지 않습니다.");
        }
        return idempotencyKey;
    }

    /** 트랜잭션 안에서 실행할 Run 생성 동작이다. */
    @FunctionalInterface
    private interface RunCreator {

        /** @return 생성된 run 식별자 */
        String create();
    }
}
