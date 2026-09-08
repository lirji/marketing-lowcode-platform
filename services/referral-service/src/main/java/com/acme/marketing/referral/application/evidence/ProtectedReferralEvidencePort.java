package com.acme.marketing.referral.application.evidence;

import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import java.util.*;

/** 完整规范证据含canonicalSubject，必须在事务外保护；加解密实现尚未接通，禁止明文落库回退。 */
public interface ProtectedReferralEvidencePort {
    /** 保护完整历史，返回稳定业务HMAC摘要及随机密文；AAD必须绑定全部Header。 */
    Sealed sealSnapshot(Header header,Snapshot snapshot);
    /** 保护合并State及原始时间精度，不能只存latest丢首次接收锚点。 */
    Sealed sealState(Header header,State state);
    /** 认证全部AAD并恢复完整Snapshot，不能把解密失败当作NotSeen。 */
    Snapshot openSnapshot(Sealed sealed);
    /** 认证全部AAD并恢复完整State，不能用当前接收时间修复历史。 */
    State openState(Sealed sealed);
    /** 状态与历史密文域分离，防止同revision跨资源移用。 */
    enum Purpose { HISTORY,CURRENT }
    /** Header只存HMAC与业务隔离键，永不含canonicalSubject原文或其普通hash。 */
    record Header(OrderKey order,String subjectKey,long keyVersion,String organizationId,String shopId,long revision,Purpose purpose,boolean quarantined) {
        /** 受信来源与保护实现均须保持完整Scope/版本，不能仅以orderId做AAD。 */
        public Header {
            Objects.requireNonNull(order);Objects.requireNonNull(purpose);if(purpose==Purpose.HISTORY && quarantined)throw invalid();
            if(subjectKey==null || !subjectKey.matches("[0-9a-f]{64}") || keyVersion<=0 || revision<=0 || organizationId==null || organizationId.isBlank() || shopId==null || shopId.isBlank())throw invalid();
        }
        /** AAD诊断不得泄露主体索引与订单信息。 */
        @Override public String toString(){return "ProtectedEvidenceHeader[redacted]";}
    }
    /** 密文封装中businessDigest是规范业务内容的受保护稳定摘要，不可用随机密文摘要替代。 */
    record Sealed(Header header,byte[] cipher,String keyId,String businessDigest) {
        /** 复制密文字节，避免事务外准备内容在CAS后被调用方修改。 */
        public Sealed {
            Objects.requireNonNull(header);
            if(cipher==null || cipher.length<16 || cipher.length>1048576 || keyId==null || keyId.isBlank() || keyId.length()>128 || businessDigest==null || !businessDigest.matches("[0-9a-f]{64}"))throw invalid();
            cipher=cipher.clone();
        }
        /** 禁止暴露内部数组形成可变准备对象。 */
        @Override public byte[] cipher(){return cipher.clone();}
        /** 不输出密文、摘要、原始Scope或密钥标识。 */
        @Override public String toString(){return "SealedReferralEvidence[redacted]";}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("evidence protection unavailable or inconsistent");}
}
