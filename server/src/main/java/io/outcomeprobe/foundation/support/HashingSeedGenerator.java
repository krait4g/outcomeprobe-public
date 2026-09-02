package io.outcomeprobe.foundation.support;

import io.outcomeprobe.foundation.SeedGenerator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 해시 기반으로 결정론적 값을 생성하는 {@link SeedGenerator} 구현이다.
 *
 * <p>책임: {@code seed + namespace + logicalKey} 조합을 해싱해 같은 입력에서 항상
 * 같은 값을 만든다. Step 추가가 기존 값에 영향을 주지 않도록 논리 키 단위로 독립 계산한다.</p>
 * <p>핵심 불변식: 전역 Random 상태를 순서대로 소비하지 않는다. 따라서 Scenario 중간에
 * Step을 삽입해도 기존 Step의 생성 값은 변하지 않는다.</p>
 */
public final class HashingSeedGenerator implements SeedGenerator {

    private static final String SEPARATOR = "/";

    /**
     * 기본 생성자다. 매 계산마다 새 {@link MessageDigest}를 만들어 상태를 공유하지 않는다.
     */
    public HashingSeedGenerator() {
    }

    /**
     * seed·namespace·logicalKey의 SHA-256 해시 앞에서 8바이트를 long으로 해석해 반환한다.
     *
     * @param seed Scenario가 고정한 결정론 seed
     * @param namespace Step 또는 도메인 단위 구분자
     * @param logicalKey 개별 값을 구분하는 논리 키
     * @return 같은 입력에 대해 항상 동일한 long 값
     */
    @Override
    public long generateLong(long seed, String namespace, String logicalKey) {
        byte[] digest = digest(seed, namespace, logicalKey);
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }

    /**
     * 해시 값을 0 이상 bound 미만의 범위로 축소해 반환한다.
     *
     * <p>음수 해시 결과가 나와도 floorMod로 항상 0 이상의 균일 분포를 유지한다.</p>
     *
     * @param seed Scenario가 고정한 결정론 seed
     * @param namespace Step 또는 도메인 단위 구분자
     * @param logicalKey 개별 값을 구분하는 논리 키
     * @param boundExclusive 결과 값의 상한(미포함), 0보다 커야 한다
     * @return 0 이상 boundExclusive 미만의 결정론적인 값
     * @throws IllegalArgumentException boundExclusive가 0 이하이면 발생한다
     */
    @Override
    public int generateInt(long seed, String namespace, String logicalKey, int boundExclusive) {
        if (boundExclusive <= 0) {
            throw new IllegalArgumentException("boundExclusive는 0보다 커야 합니다.");
        }
        long value = generateLong(seed, namespace, logicalKey);
        return Math.floorMod(value, boundExclusive);
    }

    /**
     * seed·namespace·logicalKey를 결합한 경로의 SHA-256 digest를 계산한다.
     *
     * <p>왜 필요한가: 모든 결정론 값이 이 단일 digest에서 파생돼야 논리 키 독립성이
     * 해시 충돌 수준에서 보장된다. 실패 시 상태 없이 예외를 던질 뿐 보존할 상태는 없다.</p>
     *
     * @param seed Scenario가 고정한 결정론 seed
     * @param namespace Step 또는 도메인 단위 구분자
     * @param logicalKey 개별 값을 구분하는 논리 키
     * @return 32바이트 SHA-256 digest
     */
    private byte[] digest(long seed, String namespace, String logicalKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String logicalPath = seed + SEPARATOR + namespace + SEPARATOR + logicalKey;
            return sha256.digest(logicalPath.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 의무 지원하므로 정상 환경에서 도달할 수 없다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
