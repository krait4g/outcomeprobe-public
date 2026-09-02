package io.outcomeprobe.profile;

import java.util.Optional;

/**
 * secretRef 이름을 실행 직전에 실제 secret 값으로 해석하는 출력 포트다.
 *
 * <p>구현체가 반환한 값은 요청을 구성하는 지역 변수에서만 사용해야 하며
 * Run plan·Evidence·Report·로그·예외에 저장하면 안 된다.</p>
 */
public interface SecretResolver {

    /**
     * secretRef를 해석한다.
     *
     * @param secretRef Artifact에 저장된 secret 참조 이름
     * @return 값이 존재하면 실제 secret, 설정되지 않았으면 empty
     * @throws SecretResolutionException 참조나 저장된 값이 안전하지 않으면 발생한다
     */
    Optional<String> resolve(String secretRef);

    /**
     * 필수 secretRef를 해석하고 누락을 명시적 오류로 바꾼다.
     *
     * @param secretRef Artifact에 저장된 secret 참조 이름
     * @return 실제 secret 값
     * @throws SecretResolutionException 누락되었거나 안전하게 해석할 수 없으면 발생한다
     */
    default String resolveRequired(String secretRef) {
        return resolve(secretRef).orElseThrow(() -> SecretResolutionException.missing(secretRef));
    }
}
