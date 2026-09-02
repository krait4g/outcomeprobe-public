package io.outcomeprobe.delivery.adapter.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.outcomeprobe.infrastructure.net.NetworkSafety;
import io.outcomeprobe.profile.TargetProfile;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/** Webhook TLS 인증 실패가 재시도 가능한 일반 연결 오류로 축소되지 않는지 검증한다. */
class HttpClientWebhookSenderTest {

    /** 인증서 오류 원문을 버리고 영구 TLS 실패 코드만 반환한다. */
    @Test
    void classifiesTlsValidationFailureWithoutDiagnosticLeakage() throws Exception {
        NetworkSafety networkSafety = mock(NetworkSafety.class);
        when(networkSafety.isSafe(any())).thenReturn(true);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(), any()))
                .thenThrow(new SSLHandshakeException("sensitive-certificate-diagnostic"));
        HttpClientWebhookSender sender = new HttpClientWebhookSender(networkSafety, httpClient);
        TargetProfile profile = new TargetProfile(
                "target", "https://target.example.test", List.of("/webhooks/**"),
                List.of("POST"), List.of("target.example.test"), List.of(443),
                Map.of(), Map.of(), Map.of(), List.of(), List.of());

        var result = sender.send(profile, "/webhooks/events", Map.of(), new byte[]{'{', '}'});

        assertThat(result.reached()).isFalse();
        assertThat(result.failureCode()).isEqualTo("CALLBACK_TLS_INVALID")
                .doesNotContain("certificate");
    }
}
