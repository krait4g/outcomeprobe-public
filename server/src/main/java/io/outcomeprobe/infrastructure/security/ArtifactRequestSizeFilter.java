package io.outcomeprobe.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Artifact·Target Profile YAML 본문을 String materialization 전에 512 KiB로 제한한다.
 *
 * <p>책임: Content-Length 사전 검사와 실제 ServletInputStream byte 계수를 함께 적용해
 * direct·chunked 요청 모두 같은 UTF-8 byte 상한에서 413 problem으로 종료한다.</p>
 */
final class ArtifactRequestSizeFilter extends OncePerRequestFilter {

    static final int MAXIMUM_ARTIFACT_BYTES = 512 * 1024;

    /** raw YAML 변경 endpoint가 아니면 body wrapper를 만들지 않는다. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean artifactMutation = "POST".equalsIgnoreCase(method)
                && ("/api/v1/artifacts/validate".equals(path)
                || "/api/v1/artifacts/import".equals(path));
        boolean targetProfileMutation = ("POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method))
                && ("/api/v1/target-profiles".equals(path)
                || path.startsWith("/api/v1/target-profiles/"));
        return !artifactMutation && !targetProfileMutation;
    }

    /** 선언 길이와 실제 읽은 byte 모두를 제한하고 초과 응답 원문을 고정한다. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAXIMUM_ARTIFACT_BYTES) {
            writeTooLarge(response);
            return;
        }
        try {
            // DispatcherServlet에 넘기기 전에 실제 raw stream을 읽어 MVC 예외 변환의 500 우회를 막는다.
            byte[] body = readBoundedBody(request);
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        } catch (ArtifactRequestTooLargeException exception) {
            writeTooLarge(response);
        }
    }

    /** chunked 포함 실제 request stream을 상한+1까지만 읽고 raw byte로 보존한다. */
    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        try (ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            ServletInputStream input = request.getInputStream();
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            while (true) {
                int maximumRead = Math.min(buffer.length, MAXIMUM_ARTIFACT_BYTES - total + 1);
                int read = input.read(buffer, 0, maximumRead);
                if (read < 0) {
                    return body.toByteArray();
                }
                total += read;
                if (total > MAXIMUM_ARTIFACT_BYTES) {
                    throw new ArtifactRequestTooLargeException();
                }
                body.write(buffer, 0, read);
            }
        }
    }

    /** 원격 body나 내부 예외를 반사하지 않는 고정 413 problem을 쓴다. */
    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Artifact request too large",\
                "status":413,"code":"ARTIFACT_REQUEST_TOO_LARGE"}
                """);
        response.flushBuffer();
    }

    /** request reader와 input stream이 같은 제한 stream을 공유하게 한다. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        /** 원본 요청 metadata와 상한 안에서 읽은 독립 body 사본을 보관한다. */
        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        /** 매 호출에 body 처음부터 읽는 독립 Servlet stream을 반환한다. */
        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        /** Artifact 계약의 UTF-8 reader를 bounded body 사본 위에 만든다. */
        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    /** bounded byte 사본을 downstream message converter에 다시 제공한다. */
    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        /** downstream 전용 byte 배열 stream을 초기화한다. */
        private CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        /** 다음 한 byte를 반환하고 EOF에서는 -1을 반환한다. */
        @Override
        public int read() {
            return delegate.read();
        }

        /** 요청한 구간에 남은 body byte를 복사하고 읽은 수를 반환한다. */
        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        /** cached body를 모두 소비했는지 반환한다. */
        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        /** 메모리 사본은 blocking 없이 항상 읽을 준비가 되어 있다. */
        @Override
        public boolean isReady() {
            return true;
        }

        /** 비동기 listener 등록은 이 동기 Artifact 경계에서 명시적으로 거부한다. */
        @Override
        public void setReadListener(ReadListener readListener) {
            throw new IllegalStateException("비동기 Artifact body 읽기는 지원하지 않습니다.");
        }
    }

    /** 실제 stream byte 상한 초과를 외부 상세 없이 전달하는 내부 표식이다. */
    private static final class ArtifactRequestTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;
    }
}
