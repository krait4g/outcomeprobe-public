package io.outcomeprobe.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Artifact request의 선언 길이·chunked 실제 byte 상한을 검증한다.
 *
 * <p>검출 가능한 결함: 문자 수로만 제한해 multi-byte UTF-8 또는 Content-Length 없는
 * direct 요청이 512 KiB를 넘겨 String으로 materialize되는 문제다.</p>
 */
class ArtifactRequestSizeFilterTest {

    /** exact 512 KiB UTF-8 body는 downstream이 전부 읽을 수 있다. */
    @Test
    void acceptsExactUtf8ByteBoundary() throws Exception {
        byte[] body = "가".repeat(174_762).concat("aa")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(body).hasSize(ArtifactRequestSizeFilter.MAXIMUM_ARTIFACT_BYTES);
        MockHttpServletRequest request = request(body, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger consumed = new AtomicInteger();

        new ArtifactRequestSizeFilter().doFilter(request, response, (wrapped, output) -> {
            consumed.set(wrapped.getInputStream().readAllBytes().length);
            ((MockHttpServletResponse) output).setStatus(204);
        });

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(consumed).hasValue(body.length);
    }

    /** Content-Length 없는 chunked 형태도 512 KiB+1 실제 byte에서 413으로 닫는다. */
    @Test
    void rejectsChunkedActualBytesBeforeMaterialization() throws Exception {
        byte[] body = "a".repeat(ArtifactRequestSizeFilter.MAXIMUM_ARTIFACT_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(body, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ArtifactRequestSizeFilter().doFilter(request, response,
                (wrapped, output) -> wrapped.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("ARTIFACT_REQUEST_TOO_LARGE")
                .doesNotContain("aaaa");
    }

    /** URL decode 뒤 같은 Artifact route인 alias도 raw URI 비교를 우회하지 못한다. */
    @Test
    void rejectsEncodedArtifactRouteAlias() throws Exception {
        byte[] body = oversizedBody();
        MockHttpServletRequest request = request(body, true);
        request.setRequestURI("/api/v1/artifacts/%69mport");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ArtifactRequestSizeFilter().doFilter(request, response,
                (wrapped, output) -> wrapped.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** Target Profile create·update의 String body도 같은 chunked byte 상한으로 닫는다. */
    @Test
    void rejectsOversizedTargetProfileMutations() throws Exception {
        for (String methodAndPath : new String[] {
                "POST /api/v1/target-profiles",
                "PUT /api/v1/target-profiles/example-profile"
        }) {
            String[] parts = methodAndPath.split(" ", 2);
            MockHttpServletRequest request = request(oversizedBody(), true);
            request.setMethod(parts[0]);
            request.setRequestURI(parts[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new ArtifactRequestSizeFilter().doFilter(request, response,
                    (wrapped, output) -> wrapped.getInputStream().readAllBytes());

            assertThat(response.getStatus()).as(methodAndPath).isEqualTo(413);
        }
    }

    /** 실제 byte 상한을 한 byte 넘긴 최소 실패 본문을 만든다. */
    private byte[] oversizedBody() {
        return "a".repeat(ArtifactRequestSizeFilter.MAXIMUM_ARTIFACT_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 테스트 요청을 만들고 필요하면 선언 길이를 숨겨 chunked 경계를 모사한다. */
    private MockHttpServletRequest request(byte[] body, boolean unknownLength) {
        MockHttpServletRequest request = unknownLength
                ? new MockHttpServletRequest() {
                    /** chunked 요청처럼 선언된 long 길이가 없음을 모사한다. */
                    @Override
                    public long getContentLengthLong() {
                        return -1L;
                    }

                    /** chunked 요청처럼 선언된 int 길이가 없음을 모사한다. */
                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                }
                : new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/artifacts/import");
        request.setContentType("text/plain; charset=UTF-8");
        request.setContent(body);
        return request;
    }
}
