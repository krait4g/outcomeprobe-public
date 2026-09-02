package io.outcomeprobe.emulator;

import java.util.List;
import java.util.Optional;

/**
 * Target 호출 expectation을 등록·매칭·판정하는 포트다.
 *
 * <p>책임: provider.expect Step이 등록한 기대를 Emulator ingress가 조회하고,
 * 매칭 시 횟수를 원자 증가시키고, Run 종료 시 미충족 기대를 보고한다.</p>
 * <p>핵심 불변식: 동일 operation에 여러 expectation이 동시에 매칭 조건을
 * 만족하면 ambiguous로 처리해 조용한 잘못된 매칭을 막는다.</p>
 */
public interface ExpectedInvocationPort {

    /**
     * provider.expect Step 처리 시 expectation을 등록한다.
     *
     * @param expectation 등록할 expectation, id·version은 구현이 채운다
     * @return 식별자가 채워진 expectation
     */
    ExpectedInvocation register(ExpectedInvocation expectation);

    /**
     * operation key로 아직 용량이 남은 expectation을 등록 순서대로 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey Contract operation key
     * @return 후보 expectation 목록
     */
    List<ExpectedInvocation> candidates(String runId, String operationKey);

    /**
     * expectation의 matched count를 CAS로 증가시킨다.
     *
     * @param id expectation 식별자
     * @param expectedVersion 읽은 시점의 version
     * @return 증가 성공 여부
     */
    boolean recordMatch(String id, long expectedVersion);

    /**
     * Run의 미충족 expectation을 조회한다.
     *
     * @param runId Run 식별자
     * @return 최소 횟수에 도달하지 못한 expectation 목록
     */
    List<ExpectedInvocation> unsatisfied(String runId);

    /**
     * Run에 등록된 특정 operation expectation이 존재하는지 조회한다.
     *
     * @param runId Run 식별자
     * @param operationKey Contract operation key
     * @return 존재하면 첫 expectation
     */
    Optional<ExpectedInvocation> firstByOperation(String runId, String operationKey);
}
