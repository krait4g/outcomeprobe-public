package io.outcomeprobe.cli;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Pattern;
import picocli.CommandLine.Option;

/**
 * 모든 CLI 하위 명령이 공유하는 Control API 연결 설정이다.
 *
 * <p>책임: 서버 URL과 단일 운영자 Bearer token의 출처·우선순위를 한 곳에서
 * 결정한다. 핵심 불변식은 token 원문을 명령행 인수로 받거나 진단에 출력하지 않고,
 * 명시적인 token 파일이 환경변수보다 우선하는 것이다.</p>
 */
public final class CliConnectionOptions {

    /** Control API Bearer token을 전달하는 환경변수 이름이다. */
    public static final String CONTROL_API_TOKEN_ENV = "OUTCOMEPROBE_CONTROL_API_TOKEN";

    private static final String SERVER_URL_ENV = "OUTCOMEPROBE_SERVER_URL";
    private static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final int MIN_TOKEN_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_TOKEN_FILE_BYTES = MAX_TOKEN_LENGTH + 2;
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[A-Za-z0-9._~+/\\-]+={0,2}");

    @Option(names = "--server",
            description = "서버 URL (기본: 환경변수 또는 http://localhost:8080)")
    private String server;

    @Option(names = "--token-file",
            description = "Control API Bearer token UTF-8 파일 (환경변수보다 우선)")
    private Path tokenFile;

    private final Function<String, String> environmentLookup;

    /**
     * 실제 프로세스 환경변수를 조회하는 기본 연결 옵션을 만든다.
     */
    public CliConnectionOptions() {
        this(System::getenv);
    }

    /**
     * 테스트 가능한 환경변수 조회기를 주입한다.
     *
     * @param environmentLookup 환경변수 이름별 값 조회 함수
     */
    CliConnectionOptions(Function<String, String> environmentLookup) {
        this.environmentLookup = environmentLookup;
    }

    /**
     * 테스트에서 명시 옵션과 환경변수 우선순위를 재현할 연결 설정을 만든다.
     *
     * @param environmentLookup 환경변수 이름별 값 조회 함수
     * @param server 명시 서버 URL, 없으면 null
     * @param tokenFile 명시 token 파일, 없으면 null
     */
    CliConnectionOptions(Function<String, String> environmentLookup,
                         String server,
                         Path tokenFile) {
        this.environmentLookup = environmentLookup;
        this.server = server;
        this.tokenFile = tokenFile;
    }

    /**
     * token 파일과 환경변수를 검증한 뒤 인증된 서버 client를 만든다.
     *
     * <p>명시 파일이 있으면 환경변수를 보지 않아 잘못된 파일 설정을 조용히 우회하지 않는다.</p>
     *
     * @return 모든 요청에 Bearer header를 넣는 서버 client
     * @throws CliConfigurationException token이 누락됐거나 안전한 문법을 벗어난 경우
     */
    public ServerClient createClient() {
        return new ServerClient(resolveServerUrl(), resolveControlApiToken());
    }

    /**
     * 옵션·환경변수·로컬 기본값 순서로 서버 URL을 결정한다.
     *
     * @return Control API 서버 URL
     */
    public String resolveServerUrl() {
        String resolved;
        if (server != null && !server.isBlank()) {
            resolved = server;
        } else {
            String environmentUrl = environmentLookup.apply(SERVER_URL_ENV);
            resolved = environmentUrl == null || environmentUrl.isBlank()
                    ? DEFAULT_SERVER_URL : environmentUrl;
        }
        try {
            URI uri = URI.create(resolved);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("unsafe server URL");
            }
            return resolved;
        } catch (RuntimeException exception) {
            throw new CliConfigurationException(
                    "서버 URL이 올바르지 않습니다. credential 없는 http 또는 https URL을 사용하세요.");
        }
    }

    /**
     * 명시 token 파일 또는 환경변수에서 credential을 읽고 동일 문법으로 검증한다.
     *
     * @return header에 사용할 검증된 token 원문
     * @throws CliConfigurationException token 누락·파일 오류·문법 오류가 있는 경우
     */
    String resolveControlApiToken() {
        String candidate = tokenFile == null
                ? environmentLookup.apply(CONTROL_API_TOKEN_ENV)
                : readTokenFile(tokenFile);
        if (candidate == null) {
            throw new CliConfigurationException(
                    "Control API token이 필요합니다. " + CONTROL_API_TOKEN_ENV
                            + " 또는 --token-file을 설정하세요.");
        }
        String token = removeSingleTrailingLineEnding(candidate);
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN_PATTERN.matcher(token).matches()) {
            throw new CliConfigurationException(
                    "Control API token 형식이 올바르지 않습니다. "
                            + "32~512자의 token68 형식을 사용하세요.");
        }
        return token;
    }

    /**
     * 작은 regular UTF-8 파일만 읽어 token 후보를 반환한다.
     *
     * @param path 사용자가 명시한 token 파일
     * @return 아직 마지막 줄바꿈을 제거하지 않은 파일 내용
     * @throws CliConfigurationException 파일을 안전하게 읽을 수 없는 경우
     */
    private String readTokenFile(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_TOKEN_FILE_BYTES) {
                throw new CliConfigurationException(
                        "Control API token 파일을 안전하게 읽을 수 없습니다.");
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                // size 확인 뒤 파일이 바뀌어도 제한보다 많이 메모리에 읽지 않는다.
                bytes = input.readNBytes(MAX_TOKEN_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_TOKEN_FILE_BYTES) {
                throw new CliConfigurationException(
                        "Control API token 파일을 안전하게 읽을 수 없습니다.");
            }
            return decodeUtf8(bytes);
        } catch (CliConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new CliConfigurationException(
                    "Control API token 파일을 안전하게 읽을 수 없습니다.");
        }
    }

    /**
     * 잘못된 UTF-8을 replacement 문자로 숨기지 않고 설정 오류로 바꾼다.
     *
     * @param bytes token 파일의 제한된 원본 바이트
     * @return 엄격하게 해석한 문자열
     * @throws CliConfigurationException UTF-8이 올바르지 않은 경우
     */
    private String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw new CliConfigurationException(
                    "Control API token 파일을 안전하게 읽을 수 없습니다.");
        }
    }

    /**
     * secret mount와 편집기가 붙이는 마지막 LF 또는 CRLF 한 번만 제거한다.
     *
     * @param value 환경변수 또는 파일의 token 후보
     * @return 내용의 다른 공백은 그대로 둔 token 후보
     */
    private String removeSingleTrailingLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
