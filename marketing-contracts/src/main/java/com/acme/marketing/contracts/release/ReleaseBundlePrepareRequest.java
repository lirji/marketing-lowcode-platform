package com.acme.marketing.contracts.release;

/** 指定完整组合及本站精确成员；不允许客户端只传摘要代替签名闭包。 */
public record ReleaseBundlePrepareRequest(ReleaseBundleManifest bundle, String memberManifestId) {
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    public ReleaseBundlePrepareRequest {
        if (bundle == null || memberManifestId == null || !memberManifestId.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw ReleaseBundleProtocolError.REQUEST_INVALID.invalid();
    }

    /** 必须从签名组合内选择，不能拼接调用者另传的清单。 */
    public ReleaseManifest member() {
        return bundle.members().stream().filter(item -> memberManifestId.equals(item.manifestId())).findFirst()
                .orElseThrow(ReleaseBundleProtocolError.MEMBER_MISMATCH::conflict);
    }
}
