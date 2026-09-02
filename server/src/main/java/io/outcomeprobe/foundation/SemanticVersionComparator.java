package io.outcomeprobe.foundation;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * Artifact의 SemVer 문자열을 수치·pre-release 의미에 맞게 비교한다.
 *
 * <p>문자열 비교는 {@code 1.10.0}을 {@code 1.9.0}보다 이전으로 판단한다. Target
 * Profile의 활성 최신 버전 선택이 실행 결과를 바꾸므로 이 비교는 결정론 경계다.</p>
 */
public final class SemanticVersionComparator implements Comparator<String> {

    public static final SemanticVersionComparator INSTANCE = new SemanticVersionComparator();

    /** singleton 외부 인스턴스 생성을 막는다. */
    private SemanticVersionComparator() {
    }

    /**
     * 두 SemVer의 precedence를 비교한다. build metadata는 SemVer 규칙대로 precedence에
     * 포함하지 않으므로 호출자가 Artifact 선택에 사용할 때는 원문 version tie-break를
     * 추가해야 한다.
     *
     * @param left 왼쪽 SemVer
     * @param right 오른쪽 SemVer
     * @return 왼쪽이 크면 양수, 같으면 0, 작으면 음수
     */
    @Override
    public int compare(String left, String right) {
        Version a = Version.parse(left);
        Version b = Version.parse(right);
        int core = a.major.compareTo(b.major);
        if (core == 0) {
            core = a.minor.compareTo(b.minor);
        }
        if (core == 0) {
            core = a.patch.compareTo(b.patch);
        }
        if (core != 0) {
            return core;
        }
        if (a.preRelease == null && b.preRelease == null) {
            return 0;
        }
        if (a.preRelease == null) {
            return 1;
        }
        if (b.preRelease == null) {
            return -1;
        }
        String[] aParts = a.preRelease.split("\\.");
        String[] bParts = b.preRelease.split("\\.");
        int length = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < length; i++) {
            if (i >= aParts.length) {
                return -1;
            }
            if (i >= bParts.length) {
                return 1;
            }
            int part = compareIdentifier(aParts[i], bParts[i]);
            if (part != 0) {
                return part;
            }
        }
        return 0;
    }

    /**
     * pre-release 식별자 하나를 SemVer 수치/문자 규칙으로 비교한다.
     *
     * @param left 왼쪽 식별자
     * @param right 오른쪽 식별자
     * @return 비교 결과
     */
    private int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.matches("\\d+");
        boolean rightNumeric = right.matches("\\d+");
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric) {
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    /**
     * 비교에 필요한 SemVer core와 pre-release 구성 요소다.
     *
     * @param major major 수치
     * @param minor minor 수치
     * @param patch patch 수치
     * @param preRelease pre-release 식별자, 정식 release면 null
     */
    private record Version(BigInteger major, BigInteger minor, BigInteger patch, String preRelease) {
        /**
         * 검증된 SemVer 문자열을 비교용 구성 요소로 분해한다.
         *
         * @param value SemVer 문자열
         * @return 비교용 version 값
         */
        private static Version parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("SemVer 값은 null일 수 없습니다.");
            }
            String withoutBuild = value.split("\\+", 2)[0];
            String[] releaseParts = withoutBuild.split("-", 2);
            String[] core = releaseParts[0].split("\\.");
            if (core.length != 3) {
                throw new IllegalArgumentException("지원하지 않는 SemVer입니다: " + value);
            }
            return new Version(new BigInteger(core[0]), new BigInteger(core[1]),
                    new BigInteger(core[2]), releaseParts.length == 2 ? releaseParts[1] : null);
        }
    }
}
