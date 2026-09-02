package io.outcomeprobe.probe;

import tools.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * State Probe 한 번의 관측 결과다.
 *
 * <p>책임: 요청·응답 시각, HTTP 상태, 원본 바이트 해시와 메모리 안의 파싱 JSON을
 * 묶어 assertion 평가와 안전한 Evidence 기록에 전달한다.</p>
 * <p>외부에서 오해하기 쉬운 한계: HTTP 2xx는 수렴 성공이 아니다. 최종 성공
 * 여부는 이 관측값에 대한 assertion 평가로만 판정한다.</p>
 *
 * @param requestedAt 요청 시작 관측 시각
 * @param respondedAt 응답 또는 실패 관측 시각
 * @param statusCode HTTP 상태 코드, 연결 실패 시 0
 * @param responseHash 완전히 받은 원본 응답 바이트 SHA-256, 없으면 null
 * @param body 메모리에서만 사용하는 파싱 JSON 트리, 파싱 실패 시 null
 * @param failureCode 관측 실패 코드, 정상 관측이면 null
 */
public record ProbeObservation(
        Instant requestedAt,
        Instant respondedAt,
        int statusCode,
        String responseHash,
        JsonNode body,
        String failureCode) {

    /**
     * HTTP 응답을 정상 수신해 JSON 파싱까지 마쳤는지 여부다.
     *
     * @return 유효한 관측이면 true
     */
    public boolean isValid() {
        return failureCode == null;
    }
}
