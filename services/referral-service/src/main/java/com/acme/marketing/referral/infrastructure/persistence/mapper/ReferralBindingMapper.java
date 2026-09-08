package com.acme.marketing.referral.infrastructure.persistence.mapper;
import com.acme.marketing.referral.application.ReferralBindingRepository.*;
import com.acme.marketing.referral.domain.ReferralRelation;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
/** 绑定SQL统一放XML，不提供归因改写、角色转换或删除入口。 */
@Mapper
public interface ReferralBindingMapper {
    /** 首次命令键占位与关系提交同事务。 */
    int reserveCommand(Map<String,Object> values);
    /** 锁后读取固定摘要与原关系引用。 */
    Command command(Map<String,Object> values);
    /** 只创建被邀请人角色，不覆盖任何既有角色。 */
    int reserveInvitee(Map<String,Object> values);
    /** 调用方按HMAC排序逐个调用，锁住现有角色。 */
    Role role(Map<String,Object> values);
    /** 通过唯一好友或原关系ID定位并锁读，不丢失归因。 */
    ReferralRelation relation(Map<String,Object> values);
    /** 首次保存归因及受保护好友主体。 */
    int insertRelation(Map<String,Object> values);
    /** 仅将尚未有关联的INVITEE角色关联原关系。 */
    int attachInvitee(Map<String,Object> values);
    /** 追加不含主体或原token的领域审计。 */
    int audit(Map<String,Object> values);
    /** 追加内部待评估绑定事实，不命名为资格成功或奖励。 */
    int outbox(Map<String,Object> values);
    /** 完成永久业务回执，不淘汰原请求摘要。 */
    int complete(Map<String,Object> values);
}
