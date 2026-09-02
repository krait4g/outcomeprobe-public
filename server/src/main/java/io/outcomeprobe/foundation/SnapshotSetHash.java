package io.outcomeprobe.foundation;

/**
 * Run에 고정된 세 Artifact snapshot을 하나의 상위 SHA-256으로 결합한다.
 */
public final class SnapshotSetHash {

    /** 유틸리티 인스턴스 생성을 막는다. */
    private SnapshotSetHash() {
    }

    /**
     * Plan schema version과 세 canonical snapshot hash를 순서 고정 material로 결합한다.
     *
     * @param planSchemaVersion compiled plan schema version
     * @param scenarioSnapshotHash Scenario canonical snapshot hash
     * @param contractSnapshotHash ProviderContract canonical snapshot hash
     * @param profileSnapshotHash TargetProfile canonical snapshot hash
     * @return snapshot set SHA-256
     */
    public static String calculate(int planSchemaVersion, String scenarioSnapshotHash,
                                   String contractSnapshotHash, String profileSnapshotHash) {
        String material = planSchemaVersion + "\n"
                + scenarioSnapshotHash + "\n"
                + contractSnapshotHash + "\n"
                + profileSnapshotHash;
        return HashValue.sha256(material).hexValue();
    }
}
