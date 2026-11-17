package com.acme.marketing.contracts.release;

/** 仅由已完成主体映射的可信业务入口提交；不允许客户端指定版本或租户。 */
public record ReleaseBundleAdmissionRequest(String campaignId, String participationId, String subjectToken) {
    public ReleaseBundleAdmissionRequest {
        ReleaseBundleParticipation.requireAdmissionInput(campaignId, participationId, subjectToken);
    }
}
