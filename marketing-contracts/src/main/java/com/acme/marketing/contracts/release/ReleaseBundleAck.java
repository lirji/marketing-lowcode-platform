package com.acme.marketing.contracts.release;

/** 就绪证明同时绑定完整组合和精确成员，旧RuntimeAck单独出现不满足此合同。 */
public record ReleaseBundleAck(String bundleId, String campaignId, long version, String contentHash,
        String memberSignature, RuntimeAck runtimeAck, String signature) {
    private static final String INVALID = "activity release acknowledgement is invalid";
    public ReleaseBundleAck {
        if (!identifier(bundleId) || !identifier(campaignId) || version < 1 || contentHash == null
                || !contentHash.matches("[a-f0-9]{64}") || memberSignature == null || memberSignature.isBlank()
                || runtimeAck == null || runtimeAck.status() != RuntimeAck.Status.READY || runtimeAck.capacity() < 1
                || runtimeAck.signature().isBlank()) throw new IllegalArgumentException(INVALID);
        signature = signature == null ? "" : signature;
    }

    /** 运行端只为组合内已经核验的成员构造证明，避免把别的generation的ACK套入组合。 */
    public static ReleaseBundleAck unsigned(ReleaseBundleManifest bundle, ReleaseManifest member, RuntimeAck ack) {
        if (!bundle.members().contains(member) || !member.manifestId().equals(ack.manifestId())
                || member.generation() != ack.generation() || !member.cell().equals(ack.cell()))
            throw new IllegalArgumentException(INVALID);
        return new ReleaseBundleAck(bundle.bundleId(), bundle.campaignId(), bundle.version(), bundle.contentHash(),
                member.signature(), ack, "");
    }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,64}");
    }
}
