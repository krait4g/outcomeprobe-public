package io.outcomeprobe.foundation;

/**
 * Scenario seed와 논리 키로부터 결정론적인 값을 생성하는 포트이다.
 *
 * <p>책임: Event ID, transaction ID, callback jitter, reorder 순서처럼 Scenario 실행 중에
 * 필요한 무작위성 있는 값을 같은 seed에서 항상 같은 결과로 재현한다.</p>
 * <p>핵심 불변식: 하나의 전역 Random을 순서대로 소비하지 않는다. 모든 값은
 * {@code seed + namespace + logicalKey} 해시로 독립 계산되어, Step을 추가해도
 * 기존 값이 바뀌지 않는다.</p>
 * <p>외부에서 오해하기 쉬운 한계: 이 생성기는 실제 난수 대체재가 아니다.
 * 보안 목적의 무작위 값 생성에 사용하지 않는다.</p>
 */
public interface SeedGenerator {

    /**
     * seed와 논리 경로로부터 결정론적인 long 값을 생성한다.
     *
     * @param seed Scenario가 고정한 결정론 seed
     * @param namespace Step 또는 도메인 단위 구분자
     * @param logicalKey 개별 값을 구분하는 논리 키
     * @return 같은 입력에 대해 항상 동일한 long 값
     */
    long generateLong(long seed, String namespace, String logicalKey);

    /**
     * seed와 논리 경로로부터 0 이상 bound 미만의 결정론적인 int 값을 생성한다.
     *
     * <p>delay jitter나 reorder index 계산처럼 범위가 필요한 값에 사용한다.</p>
     *
     * @param seed Scenario가 고정한 결정론 seed
     * @param namespace Step 또는 도메인 단위 구분자
     * @param logicalKey 개별 값을 구분하는 논리 키
     * @param boundExclusive 결과 값의 상한(미포함), 0보다 커야 한다
     * @return 0 이상 boundExclusive 미만의 결정론적인 값
     */
    int generateInt(long seed, String namespace, String logicalKey, int boundExclusive);
}
