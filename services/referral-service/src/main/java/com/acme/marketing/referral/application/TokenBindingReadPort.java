package com.acme.marketing.referral.application;
import com.acme.marketing.referral.application.ReferralInviteRepository.Token;
/** 分享token的只读绑定边界；多好友可以复用，禁止引入订单归因式单次消费。 */
public interface TokenBindingReadPort {
    /** 事务前按租户+原token摘要定位，不代表最终有效性。 */
    Token locate(String tenant,String tokenHash);
    /** 必须位于本地主库事务且在participant锁之后；返回当前可复检的token身份，不消费。 */
    Token lock(String tenant,String tokenHash);
}
