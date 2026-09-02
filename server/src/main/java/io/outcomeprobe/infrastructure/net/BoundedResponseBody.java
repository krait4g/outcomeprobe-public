package io.outcomeprobe.infrastructure.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 응답 스트림을 바이트 상한 안에서만 메모리에 보관한다.
 *
 * <p>책임: 문자 수가 아닌 원본 바이트 수로 최대 크기를 판정하고, 상한을 넘은
 * 응답은 일부 내용조차 호출자에게 노출하지 않는다.</p>
 * <p>핵심 불변식: 최대 허용 크기보다 한 바이트만 더 읽어 초과 여부를 판정하며,
 * 초과 결과에는 원문 바이트가 남지 않는다. 스트림 종료 책임은 호출자에게 있다.</p>
 *
 * @param tooLarge 허용 상한을 초과했는지 여부
 * @param bytes 상한 안에서 완전히 읽은 원본 바이트, 초과 시 빈 배열
 */
public record BoundedResponseBody(boolean tooLarge, byte[] bytes) {

    /**
     * 가변 바이트 배열을 방어적으로 복사해 결과의 불변성을 보장한다.
     *
     * @param tooLarge 허용 상한 초과 여부
     * @param bytes 읽은 응답 바이트
     */
    public BoundedResponseBody {
        bytes = bytes == null ? new byte[0] : bytes.clone();
        if (tooLarge && bytes.length != 0) {
            throw new IllegalArgumentException("크기 초과 결과는 응답 바이트를 보관할 수 없습니다.");
        }
    }

    /**
     * 응답 스트림에서 최대 크기보다 한 바이트까지 읽어 초과 여부를 판정한다.
     *
     * <p>초과 응답의 앞부분을 반환하지 않으므로 Evidence나 오류 상세에 부분 원문이
     * 실수로 저장되는 경로를 차단한다.</p>
     *
     * @param input 읽을 HTTP 응답 스트림
     * @param maximumBytes 허용할 최대 바이트 수
     * @return 상한 내 전체 바이트 또는 원문이 비어 있는 초과 결과
     * @throws IOException 스트림 읽기에 실패하면 발생한다
     */
    public static BoundedResponseBody read(InputStream input, int maximumBytes) throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException("응답 바이트 상한은 0 이상이어야 합니다.");
        }
        byte[] captured = input.readNBytes(Math.addExact(maximumBytes, 1));
        if (captured.length > maximumBytes) {
            // 초과 응답은 앞부분조차 보존하지 않아 이후 계층으로 원문이 새지 않게 한다.
            return new BoundedResponseBody(true, new byte[0]);
        }
        return new BoundedResponseBody(false, captured);
    }

    /**
     * 보관한 바이트를 방어적으로 복사해 반환한다.
     *
     * @return 호출자가 변경해도 내부 값에 영향을 주지 않는 바이트 배열
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * 응답 바이트를 대체 문자 없이 엄격한 UTF-8 문자열로 해석한다.
     *
     * @return UTF-8로 해석한 응답 문자열
     * @throws CharacterCodingException 유효하지 않은 UTF-8 바이트가 포함되면 발생한다
     */
    public String utf8Text() throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }
}
