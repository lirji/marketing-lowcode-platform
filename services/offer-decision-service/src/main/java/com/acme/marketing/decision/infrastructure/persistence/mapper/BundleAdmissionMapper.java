package com.acme.marketing.decision.infrastructure.persistence.mapper;

import com.acme.marketing.decision.application.BundleAdmissionRepository.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL集中在XML；每次读取绑定可信租户、运行范围和活动，不跨服务读写。 */
@Mapper
public interface BundleAdmissionMapper {
    /** 共享锁固定一个完整应用位置，不分别读取各slot latest。 */
    AppliedAuthorization lockApplied(@Param("key") Key key);
    /** 精确历史只读，不能改写首次应用事实。 */
    AppliedAuthorization applied(@Param("key") Key key, @Param("sequence") long sequence);
    /** 非锁定读配合READ_COMMITTED和唯一插入，避免空键间隙锁升级。 */
    StoredParticipation participation(@Param("key") Key key, @Param("participationId") String participationId);
    /** 一条SQL持久完整签名事实，不先插入对外可见的占位记录。 */
    int insert(@Param("key") Key key, @Param("participationId") String participationId, @Param("value") StoredParticipation value);
}
