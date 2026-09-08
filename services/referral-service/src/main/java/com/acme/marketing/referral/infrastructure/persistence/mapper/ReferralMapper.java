package com.acme.marketing.referral.infrastructure.persistence.mapper;
import com.acme.marketing.referral.application.ReferralRepository.Command;
import com.acme.marketing.referral.application.ReferralRepository.Role;
import com.acme.marketing.referral.domain.ReferralParticipant;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 裂变参与SQL仅在XML维护；所有定位键以租户开头。 */
@Mapper
public interface ReferralMapper {
    /** 共享锁保护受控索引版本不在加入提交前变化；没有锚点不得从部署配置自动补造。 */
    Long subjectIndexVersion(@Param("tenant") String tenant);
    /** 仅唯一冲突时执行无业务改变的更新，以取得同命令键锁。 */
    int reserveCommand(Map<String,Object> values);
    /** 当前读使等待其他请求提交后可见原成功回执。 */
    Command command(Map<String,Object> values);
    /** 稳定活动主体锁不包含规则版本，避免升级重复参与。 */
    int reserveRole(Map<String,Object> values);
    /** 当前读返回角色及原索引版本，不猜测合并或轮换。 */
    Role role(Map<String,Object> values);
    /** 只按租户及参与ID查询，不跨库读取交易主体。 */
    ReferralParticipant participant(@Param("tenant") String tenant,@Param("participantId") String participantId);
    /** 首次插入固定版本及受保护主体。 */
    int insertParticipant(Map<String,Object> values);
    /** 角色关联只能由首次空INVITER变成该参与者。 */
    int attachRole(Map<String,Object> values);
    /** 完成永久命令引用；已完成回执不改写。 */
    int complete(Map<String,Object> values);
    /** 追加领域审计，不开放更新或删除Mapper。 */
    int audit(Map<String,Object> values);
    /** 追加可靠发送意图；本阶段不启动relay。 */
    int outbox(Map<String,Object> values);
}
