package io.outcomeprobe.probe;

import io.outcomeprobe.profile.TargetProfile;
import java.time.Duration;
import java.util.Map;

/**
 * 허용된 Target Profile을 통해 외부에서 관측 가능한 업무 상태를 조회하는 포트다.
 *
 * <p>책임: Target 내부 저장소를 직접 읽지 않고 실제 HTTP 계약만으로 최종
 * 상태 수렴 판정 근거를 수집한다.</p>
 * <p>핵심 불변식: 구현체는 Profile의 host·path allowlist를 벗어나는 요청을
 * 보내지 않는다. redirect는 기본 추적하지 않는다.</p>
 */
public interface StateProbePort {

    /**
     * 한 번의 상태 조회를 수행하고 redaction된 관측 결과를 반환한다.
     *
     * @param profile 허용된 Target Profile
     * @param method HTTP method
     * @param path Profile 기준 상대 경로
     * @param headers 추가 요청 header
     * @param maximumDuration 연결과 응답 body 전체를 마쳐야 하는 최대 실제 시간
     * @return HTTP 결과와 파싱된 JSON 관측값
     */
    ProbeObservation probe(TargetProfile profile, String method, String path,
                           Map<String, String> headers, Duration maximumDuration);
}
