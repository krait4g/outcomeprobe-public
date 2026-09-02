package io.outcomeprobe.evidence;

/**
 * Evidence를 append-only Timeline에 기록하는 포트다.
 *
 * <p>책임: Provider commit, 응답 장애, Target 호출 같은 상태 변경과 Evidence를
 * 같은 트랜잭션에 저장할 수 있는 기록 지점을 제공한다.</p>
 * <p>핵심 불변식: 구현체는 sequence를 Run 단위 원자 연산으로 할당해 동시 append에서도
 * 순서 중복이 없어야 한다. 기록 후 수정·삭제 API는 존재하지 않는다.</p>
 */
public interface EvidenceRecorder {

    /**
     * 명령을 받아 Evidence를 한 건 append하고 저장된 사건을 반환한다.
     *
     * <p>왜 필요한가: 호출자가 할당된 sequence를 즉시 Evidence·보고에 사용할 수 있어야
     * Timeline 순서와 assertion 근거가 일치한다.</p>
     *
     * @param command 기록할 사건 명령
     * @return sequence와 관측 시각이 채워진 Evidence
     */
    EvidenceEvent append(AppendEvidenceCommand command);
}
