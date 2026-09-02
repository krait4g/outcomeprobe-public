package io.outcomeprobe.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 저장된 Evidence Timeline을 조회하는 포트다.
 *
 * <p>책임: evidence.await 매칭, evidence.assert 횟수·순서 판정, Report·Timeline API가
 * append-only 사건 이력을 읽는 유일한 통로를 제공한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 조회 결과는 snapshot이다. 반환 이후 append된
 * 사건은 포함되지 않으므로 대기 로직은 재조회로 수렴을 확인해야 한다.</p>
 */
public interface EvidenceQueryPort {

    /**
     * matcher와 일치하는 Evidence 수를 반환한다.
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 조건을 만족하는 Evidence 건수
     */
    long count(String runId, EvidenceMatcher matcher);

    /**
     * matcher와 일치하는 첫 번째 Evidence의 sequence를 반환한다.
     *
     * <p>순서(before/after) assertion의 기준점으로 사용된다.</p>
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 첫 일치 Evidence의 sequence, 없으면 empty
     */
    Optional<Long> firstSequenceOf(String runId, EvidenceMatcher matcher);

    /**
     * matcher와 일치하는 Evidence가 하나라도 존재하는지 반환한다.
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @return 존재 여부
     */
    boolean exists(String runId, EvidenceMatcher matcher);

    /**
     * matcher와 일치하면서 지정 시각까지 관측된 Evidence가 하나라도 존재하는지 반환한다.
     *
     * <p>마감 시각을 가진 대기 판정은 현재 Timeline 전체가 아니라 이 경계 안에서 실제로
     * 관측된 사건만 사용해야 한다. 경계 시각과 같은 {@code observed_at}은 포함한다.</p>
     *
     * @param runId 조회할 Run 식별자
     * @param matcher 유형·속성 조건
     * @param observedAtInclusive 포함할 마지막 관측 시각
     * @return 마감 시각까지 관측된 일치 Evidence의 존재 여부
     */
    boolean existsObservedAtOrBefore(String runId, EvidenceMatcher matcher,
                                     Instant observedAtInclusive);

    /**
     * Run의 Evidence를 sequence 오름차순으로 전부 반환한다.
     *
     * <p>Timeline API와 Report 생성에 사용한다. 대규모 Run은 향후 cursor pagination으로
     * 교체될 수 있다.</p>
     *
     * @param runId 조회할 Run 식별자
     * @return sequence 정렬된 Evidence 목록
     */
    List<EvidenceEvent> timeline(String runId);
}
