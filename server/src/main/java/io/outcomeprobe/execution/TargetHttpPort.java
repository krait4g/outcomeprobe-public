package io.outcomeprobe.execution;

import tools.jackson.databind.JsonNode;
import io.outcomeprobe.profile.TargetProfile;
import java.time.Instant;
import java.util.Map;

/**
 * Target Application에 trigger·초기화 요청을 보내는 포트다.
 *
 * <p>책임: target.http Step이 Profile allowlist 안에서 body 있는 요청을 보낼 수
 * 있게 한다. State Probe와 달리 요청 body와 임의 method를 지원한다.</p>
 * <p>핵심 불변식: 구현체는 Profile에 허용되지 않은 path로 요청하지 않는다.
 * 이 포트의 결과는 성공 증거가 아니며, 최종 판정은 Probe assertion이 담당한다.</p>
 */
public interface TargetHttpPort {

    /**
     * Target에 HTTP 요청을 한 번 보내고 관측 결과를 반환한다.
     *
     * @param profile 허용된 Target Profile
     * @param method HTTP method
     * @param path Profile 기준 상대 경로
     * @param headers 요청 header
     * @param bodyText 요청 body 문자열, 없으면 null
     * @return 응답 관측 결과
     */
    TargetHttpResponse call(TargetProfile profile, String method, String path,
                            Map<String, String> headers, String bodyText);

    /**
     * Target 요청 한 번의 관측 결과다.
     *
     * @param statusCode HTTP 상태 코드, 연결 실패 시 0
     * @param bodyText 응답 body 문자열
     * @param body 파싱된 JSON, 파싱 실패 시 null
     * @param respondedAt 응답 관측 시각
     * @param failureCode 연결·보안 실패 코드, 정상이면 null
     */
    record TargetHttpResponse(int statusCode, String bodyText, JsonNode body,
                              Instant respondedAt, String failureCode) {

        /**
         * 요청이 Target에 도달해 HTTP 응답을 받았는지 여부다.
         *
         * @return 정상 관측이면 true
         */
        public boolean reached() {
            return failureCode == null;
        }
    }
}
