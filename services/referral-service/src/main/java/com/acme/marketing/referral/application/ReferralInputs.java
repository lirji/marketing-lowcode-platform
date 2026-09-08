package com.acme.marketing.referral.application;
/** 内部机器键统一校验，SQL机器键使用ascii_bin避免大小写混淆。 */
public final class ReferralInputs {
    private ReferralInputs() {}
    /** 返回未经归一化的原始键；禁止日志拼入非法输入。 */
    public static String key(String value,int max) {
        if(value==null || value.length()>max || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]*")) throw new IllegalArgumentException("invalid referral key");
        return value;
    }
    /** HMAC/内容摘要必须完整64位小写十六进制；格式校验不证明来源可信。 */
    public static String digest(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid referral digest");
        return value;
    }
}
