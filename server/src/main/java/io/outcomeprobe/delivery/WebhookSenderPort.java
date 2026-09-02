package io.outcomeprobe.delivery;

import io.outcomeprobe.profile.TargetProfile;
import java.util.Map;

/**
 * Target Webhook endpoint로 Callback을 전송하는 포트다.
 *
 * <p>책임: Worker가 HTTP 세부 구현을 모르고 전달·응답 판정만 하게 한다.</p>
 * <p>핵심 불변식: 구현체는 Profile allowlist 밖으로 요청하지 않으며 redirect를
 * 추적하지 않는다.</p>
 */
public interface WebhookSenderPort {

    /**
     * Webhook을 한 번 전송한다.
     *
     * @param profile Target Profile
     * @param path 수신 상대 경로
     * @param headers 요청 header
     * @param body 원본 body 바이트
     * @return 전송 결과
     */
    WebhookSendResult send(TargetProfile profile, String path, Map<String, String> headers, byte[] body);

    /**
     * Webhook 전송 결과를 표현한다.
     *
     * @param statusCode HTTP 상태 코드, 연결 실패 시 0
     * @param failureCode 연결 실패 사유, 정상이면 null
     */
    record WebhookSendResult(int statusCode, String failureCode) {

        /**
         * Target에 요청이 도달해 응답을 받았는지 여부다.
         *
         * @return 도달했으면 true
         */
        public boolean reached() {
            return failureCode == null;
        }
    }
}
