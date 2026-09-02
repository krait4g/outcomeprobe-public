package io.outcomeprobe.emulator;

import java.time.Duration;
import java.util.Map;

/**
 * Emulator ingress 처리 결과를 표현한다.
 *
 * <p>책임: Provider 상태 변경(이미 commit됨)과 외부에 노출할 응답 행동을 분리해
 * 전달한다. 응답 지연은 transaction 밖에서 적용된다.</p>
 * <p>핵심 불변식: 이 객체가 반환된 시점에 Provider commit은 이미 확정적이다.
 * responseDelay 동안 Target이 연결을 끊어도 Provider truth는 되돌아가지 않는다.</p>
 *
 * @param status Target에 노출할 HTTP 상태 코드
 * @param headers 응답 header
 * @param body 응답 body 바이트, 없으면 빈 배열
 * @param responseDelay 응답을 내보내기 전에 의도적으로 소비할 시간
 * @param problemCode 거부 사유 코드, 정상 처리면 null
 */
public record EmulationOutcome(
        int status,
        Map<String, String> headers,
        byte[] body,
        Duration responseDelay,
        String problemCode) {

    /**
     * 의도적 지연 후 응답을 보내야 하는지 여부다.
     *
     * @return 지연이 있으면 true
     */
    public boolean hasResponseDelay() {
        return responseDelay != null && !responseDelay.isZero();
    }
}
