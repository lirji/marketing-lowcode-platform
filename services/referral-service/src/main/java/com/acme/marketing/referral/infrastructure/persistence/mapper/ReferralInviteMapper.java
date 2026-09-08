package com.acme.marketing.referral.infrastructure.persistence.mapper;
import com.acme.marketing.referral.application.ReferralInviteRepository.*;
import com.acme.marketing.referral.domain.ReferralParticipant;
import java.util.Map;
import org.apache.ibatis.annotations.*;
/** 邀请token SQL入口，所有定位键带租户，禁止删除永久身份或清空短期密文。 */
@Mapper
public interface ReferralInviteMapper {
    /** 非锁观察/锁后当前读复用同一回执形状。 */
    Replay replay(Map<String,Object> values);
    /** 同命令键只抢锁，不覆盖已有业务摘要。 */
    int reserve(Map<String,Object> values);
    /** 返回冻结参与快照；lock只由内部事务调用方控制。 */
    ReferralParticipant participant(Map<String,Object> values);
    /** 与参与者同一事务读取所有权，不把HMAC当公开响应。 */
    Ownership ownership(Map<String,Object> values);
    /** 写永久token摘要身份，不含原token。 */
    int insertToken(Map<String,Object> values);
    /** 首次完成专用密文回执，后续请求不可覆盖。 */
    int complete(Map<String,Object> values);
    /** token发行审计无明文或密文，只引用token ID。 */
    int audit(Map<String,Object> values);
    /** 按租户+token摘要查有效性。 */
    Token token(@Param("tenant") String tenant,@Param("tokenHash") String tokenHash);
    /** 绑定事务在participant锁之后锁读同一永久token身份，不执行消费标记。 */
    Token lockToken(@Param("tenant") String tenant,@Param("tokenHash") String tokenHash);
    /** 数据库主体所有权投影。 */
    record Ownership(String subjectKey,long keyVersion) {}
}
