package com.acme.marketing.referral.application.evidence;

import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.VerifiedInput;
import java.util.Objects;

/** 真实来源认证/范围与原始接收时间核验在事务外完成；默认未接通，禁止客户端自报verified。 */
public interface TrustedReferralEvidencePort {
    /** 校验原信封签名、issuer/tenant/事件ID及完整内容绑定，来源异常由调用方统一脱敏。 */
    Accepted verify(TenantScope scope,Envelope envelope,RequestBinding expected);
    /** 原始载荷只在调用栈保留，不允许日志/Inbox保存完整报文。 */
    record Envelope(String eventId,String assertion,String payload,String traceId) {
        /** 内部输入边界也做大小限制；实际协议来源仍需独立签收。 */
        public Envelope {
            text(eventId,128);text(traceId,64);
            if(assertion==null || assertion.isBlank() || assertion.length()>16384 || payload==null || payload.isBlank() || payload.length()>1048576)throw invalid();
        }
        /** 避免意外输出报文、断言和业务身份。 */
        @Override public String toString(){return "ReferralEvidenceEnvelope[redacted]";}
    }
    /** 报文摘要仅用于内存验签绑定；持久业务摘要必须经保护Port生成，不能普通hash主体原文。 */
    record RequestBinding(String tenantId,String eventId,String payloadDigest,String operation,String method,String path) {
        /** 未来HTTP路径须由可信BFF替换实际绑定，此内部路径不冒充已完成公网认证。 */
        public RequestBinding { text(tenantId,64);text(eventId,128);if(payloadDigest==null || !payloadDigest.matches("[0-9a-f]{64}"))throw invalid();text(operation,64);text(method,16);text(path,128); }
    }
    /** issuer不可由客户端hint直接提升为权威来源；VerifiedInput中的主体已映射为受信HMAC。 */
    record Accepted(String issuer,String eventId,RequestBinding binding,VerifiedInput input) {
        /** 精确业务绑定由应用层再核对，不自动改写大小写或Scope。 */
        public Accepted { text(issuer,128);text(eventId,128);Objects.requireNonNull(binding);Objects.requireNonNull(input); }
        /** 默认诊断不携带受信主体或载荷。 */
        @Override public String toString(){return "AcceptedReferralEvidence[redacted]";}
    }
    private static void text(String value,int maximum){if(value==null || value.isBlank() || value.length()>maximum)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid internal evidence envelope");}
}
