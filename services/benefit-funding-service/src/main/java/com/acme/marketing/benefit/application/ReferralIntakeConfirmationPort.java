package com.acme.marketing.benefit.application;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity;
import java.time.Instant;
/** 事务外固定来源的资格确认/永久恢复；未知不是拒绝，历史receipt不是当前投递许可。 */
public interface ReferralIntakeConfirmationPort {
    /** 仅在CONFIRMING已耐久提交后调用；请求幂等身份永远不含lease/fence/重试次数。 */
    Result confirm(Identity identity);
    /** 崩溃/超时/旧token过期只恢复同Identity，禁止另造reward或授权请求。 */
    Result recover(Identity identity);
    enum State { UNKNOWN,REJECTED,CONFIRMED,CANCEL_REQUESTED }
    /** 永久receipt内容与当前取消水位独立；适配器必须验证可信issuer/受众/机器Scope和整个响应。 */
    record Result(Identity identity,State currentState,long currentRevision,long cancelRevision,
            String confirmationId,long authorizationSequence,Instant confirmedAt,String rejectionId,
            Instant issuedAt,Instant expiresAt) {
        @Override public String toString(){return "ReferralConfirmationResult[redacted]";}
    }
}
