package io.outcomeprobe.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Control API token 출처·우선순위·문법의 fail-closed 계약을 검증한다.
 *
 * <p>검출 가능한 결함: token 파일을 무시하고 환경변수를 사용하거나 공백·개행·과대
 * credential을 Authorization header에 넣는 문제.</p>
 */
class CliConnectionOptionsTest {

    private static final String ENVIRONMENT_TOKEN = "E".repeat(32);
    private static final String FILE_TOKEN = "F".repeat(32) + "==";

    /**
     * 명시 파일은 환경변수보다 우선하고 마지막 줄바꿈 한 번만 제거해야 한다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @throws Exception token 파일 쓰기가 실패한 경우
     */
    @Test
    void tokenFileOverridesEnvironmentAndRemovesOneLineEnding(@TempDir Path directory)
            throws Exception {
        Path tokenFile = directory.resolve("control-api-token");
        Files.writeString(tokenFile, FILE_TOKEN + "\r\n");
        CliConnectionOptions options = options(
                Map.of(CliConnectionOptions.CONTROL_API_TOKEN_ENV, ENVIRONMENT_TOKEN),
                tokenFile);

        assertThat(options.resolveControlApiToken()).isEqualTo(FILE_TOKEN);
    }

    /**
     * 파일 옵션이 없으면 권장 환경변수의 token을 사용해야 한다.
     */
    @Test
    void environmentTokenIsUsedWithoutTokenFile() {
        CliConnectionOptions options = options(
                Map.of(CliConnectionOptions.CONTROL_API_TOKEN_ENV, ENVIRONMENT_TOKEN), null);

        assertThat(options.resolveControlApiToken()).isEqualTo(ENVIRONMENT_TOKEN);
    }

    /**
     * 환경변수 token도 마지막 줄바꿈 한 번 뒤 동일 문법으로 검증해야 한다.
     */
    @Test
    void environmentTokenAllowsOneTerminalLineEnding() {
        CliConnectionOptions options = options(Map.of(
                CliConnectionOptions.CONTROL_API_TOKEN_ENV, ENVIRONMENT_TOKEN + "\n"), null);

        assertThat(options.resolveControlApiToken()).isEqualTo(ENVIRONMENT_TOKEN);
    }

    /**
     * token이 없으면 네트워크 전에 안전한 설정 오류로 중단해야 한다.
     */
    @Test
    void missingTokenFailsWithoutExposingAValue() {
        CliConnectionOptions options = options(Map.of(), null);

        assertThatThrownBy(options::resolveControlApiToken)
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining(CliConnectionOptions.CONTROL_API_TOKEN_ENV)
                .hasMessageNotContaining("Bearer");
    }

    /**
     * 서버와 같은 32~512자 token68 문법만 허용해야 한다.
     */
    @Test
    void enforcesExactLengthAndToken68Grammar() {
        assertThat(resolve("A".repeat(32))).isEqualTo("A".repeat(32));
        assertThat(resolve("A".repeat(510) + "==")).hasSize(512);

        for (String invalid : new String[]{
                "A".repeat(31),
                "A".repeat(513),
                "A".repeat(31) + " ",
                "A".repeat(32) + "\n\n",
                "A".repeat(32) + "=A",
                "A".repeat(32) + "===",
                "A".repeat(31) + ":"
        }) {
            assertThatThrownBy(() -> resolve(invalid))
                    .isInstanceOf(CliConfigurationException.class)
                    .hasMessageNotContaining(invalid);
        }
    }

    /**
     * 잘못된 UTF-8 token 파일은 replacement 문자로 숨기지 않고 거부해야 한다.
     *
     * @param directory JUnit이 제공하는 격리 디렉터리
     * @throws Exception fixture 파일 쓰기가 실패한 경우
     */
    @Test
    void rejectsMalformedUtf8File(@TempDir Path directory) throws Exception {
        Path tokenFile = directory.resolve("invalid-token");
        Files.write(tokenFile, new byte[]{(byte) 0xc3, (byte) 0x28});

        assertThatThrownBy(() -> options(Map.of(), tokenFile).resolveControlApiToken())
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageNotContaining("�");
    }

    /**
     * 서버 URL의 user-info·query credential은 doctor나 연결 오류로 출력되기 전에 거부한다.
     */
    @Test
    void rejectsCredentialBearingServerUrlWithoutEchoingIt() {
        String credential = "url-secret-must-not-appear";
        CliConnectionOptions options = new CliConnectionOptions(
                key -> CliConnectionOptions.CONTROL_API_TOKEN_ENV.equals(key)
                        ? ENVIRONMENT_TOKEN : null,
                "http://" + credential + "@127.0.0.1:8080?token=" + credential,
                null);

        assertThatThrownBy(options::createClient)
                .isInstanceOf(CliConfigurationException.class)
                .hasMessageContaining("서버 URL")
                .hasMessageNotContaining(credential);
    }

    /**
     * 지정한 환경 map과 파일로 테스트용 연결 옵션을 만든다.
     *
     * @param environment 환경변수 이름별 값
     * @param tokenFile 명시 token 파일 또는 null
     * @return 테스트 대상 연결 옵션
     */
    private CliConnectionOptions options(Map<String, String> environment, Path tokenFile) {
        return new CliConnectionOptions(environment::get, null, tokenFile);
    }

    /**
     * 단일 환경변수 후보를 실제 resolver로 해석한다.
     *
     * @param candidate 검증할 token 후보
     * @return 검증된 token
     */
    private String resolve(String candidate) {
        return options(Map.of(CliConnectionOptions.CONTROL_API_TOKEN_ENV, candidate), null)
                .resolveControlApiToken();
    }
}
