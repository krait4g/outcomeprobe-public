package io.outcomeprobe.profile.adapter.out.secret;

import io.outcomeprobe.profile.SecretResolutionException;
import io.outcomeprobe.profile.SecretResolver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 환경변수와 설정된 mounted directory에서 secretRef를 해석한다.
 *
 * <p>정식 환경변수, mounted file, callback 전용 compatibility 환경변수 순서로 조회한다.
 * 파일의 실제 경로는 mounted root 안에 있어야 하며 경로 이탈·16KiB 초과·잘못된
 * UTF-8·본문 CR/LF를 거부한다. Kubernetes의 root 내부 symlink 구조는 허용한다.</p>
 */
@Component
public class EnvironmentFileSecretResolver implements SecretResolver {

    private static final int MAX_SECRET_BYTES = 16 * 1024;
    private static final Pattern SAFE_REFERENCE = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");
    private static final String ENV_PREFIX = "OUTCOMEPROBE_SECRET_";
    private static final String CALLBACK_REFERENCE = "callback-signing-key";
    private static final String CALLBACK_COMPATIBILITY_ENV = "OUTCOMEPROBE_DEMO_CALLBACK_SECRET";

    private final Function<String, String> environmentLookup;
    private final Path mountedDirectory;

    /**
     * 운영 환경변수 조회기와 설정된 mounted directory를 사용한다.
     *
     * @param mountedDirectory secret file directory, 비어 있으면 file 조회 비활성화
     */
    @Autowired
    public EnvironmentFileSecretResolver(
            @Value("${outcomeprobe.secrets.directory:}") String mountedDirectory) {
        this(System::getenv, mountedDirectory);
    }

    /**
     * 테스트 가능한 환경 조회 함수와 mounted directory를 주입한다.
     *
     * @param environmentLookup 환경변수 이름별 값 조회 함수
     * @param mountedDirectory secret file directory, 비어 있으면 file 조회 비활성화
     */
    EnvironmentFileSecretResolver(Function<String, String> environmentLookup,
                                  String mountedDirectory) {
        this.environmentLookup = environmentLookup;
        this.mountedDirectory = toDirectory(mountedDirectory);
    }

    /**
     * 정식 환경변수, mounted file, callback compatibility 환경변수 순서로 secret을 찾는다.
     *
     * @param secretRef Artifact에 저장된 secret 참조 이름
     * @return 값이 설정돼 있으면 실제 secret, 없으면 empty
     */
    @Override
    public Optional<String> resolve(String secretRef) {
        validateReference(secretRef);
        String canonicalEnvironmentName = ENV_PREFIX + secretRef.toUpperCase(Locale.ROOT)
                .replace('-', '_');
        Optional<String> canonical = environmentValue(canonicalEnvironmentName, secretRef);
        if (canonical.isPresent()) {
            return canonical;
        }
        Optional<String> mounted = mountedValue(secretRef);
        if (mounted.isPresent()) {
            return mounted;
        }
        if (CALLBACK_REFERENCE.equals(secretRef)) {
            return environmentValue(CALLBACK_COMPATIBILITY_ENV, secretRef);
        }
        return Optional.empty();
    }

    /** secretRef를 파일명과 환경변수 suffix로 안전하게 사용할 수 있는지 검사한다. */
    private void validateReference(String secretRef) {
        if (secretRef == null || !SAFE_REFERENCE.matcher(secretRef).matches()) {
            throw SecretResolutionException.invalidReference();
        }
    }

    /** 환경변수가 존재하면 빈 값·과대·개행을 검사한 뒤 반환한다. */
    private Optional<String> environmentValue(String environmentName, String secretRef) {
        String value = environmentLookup.apply(environmentName);
        if (value == null) {
            return Optional.empty();
        }
        validateValue(value, secretRef);
        return Optional.of(value);
    }

    /** mounted directory의 secretRef 파일을 안전하게 읽는다. */
    private Optional<String> mountedValue(String secretRef) {
        if (mountedDirectory == null) {
            return Optional.empty();
        }
        Path candidate = mountedDirectory.resolve(secretRef).normalize();
        if (!candidate.startsWith(mountedDirectory)) {
            throw SecretResolutionException.sourceUnavailable(secretRef);
        }
        if (!Files.exists(candidate)) {
            return Optional.empty();
        }
        try {
            Path realDirectory = mountedDirectory.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realDirectory)
                    || !Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                throw SecretResolutionException.sourceUnavailable(secretRef);
            }
            long size = Files.size(realCandidate);
            if (size <= 0 || size > MAX_SECRET_BYTES) {
                throw SecretResolutionException.invalidValue(secretRef);
            }
            byte[] bytes = Files.readAllBytes(realCandidate);
            if (bytes.length > MAX_SECRET_BYTES) {
                throw SecretResolutionException.invalidValue(secretRef);
            }
            String value = removeSingleTrailingLineEnding(decodeUtf8(bytes, secretRef));
            validateValue(value, secretRef);
            return Optional.of(value);
        } catch (SecretResolutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw SecretResolutionException.sourceUnavailable(secretRef);
        }
    }

    /** UTF-8 오류를 replacement 문자로 숨기지 않고 안전한 설정 오류로 바꾼다. */
    private String decodeUtf8(byte[] bytes, String secretRef) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException exception) {
            throw SecretResolutionException.invalidValue(secretRef);
        }
    }

    /** Docker·Kubernetes secret mount가 붙이는 마지막 LF 또는 CRLF 한 번만 제거한다. */
    private String removeSingleTrailingLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 값의 빈 문자열·UTF-8 byte 크기·header injection 개행을 거부한다. */
    private void validateValue(String value, String secretRef) {
        if (value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw SecretResolutionException.invalidValue(secretRef);
        }
    }

    /** 설정 문자열을 절대 정규화 경로로 바꾸고 빈 설정은 비활성화한다. */
    private static Path toDirectory(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("mounted secret directory 설정이 올바르지 않습니다.");
        }
    }
}
