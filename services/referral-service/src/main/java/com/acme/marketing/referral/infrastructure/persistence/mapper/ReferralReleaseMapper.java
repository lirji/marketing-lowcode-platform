package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.release.ReferralReleaseRepository.Stored;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 只追加已验制品；重复代次通过唯一键等待原事务，不覆盖可信历史。 */
@Mapper
public interface ReferralReleaseMapper {
    /** 同键保留原始内容；非唯一键相关错误必须向上传播。 */
    int reserve(Map<String, Object> values);
    /** 必须在 reserve 的同一本地事务内锁读，判定永久成功重放或冲突。 */
    Stored lock(Map<String, Object> values);
}
