package com.acme.marketing.decision.infrastructure.persistence.mapper;

import com.acme.marketing.decision.application.BundleDirectiveRepository.Key;
import com.acme.marketing.decision.application.BundleDirectiveRepository.StoredDirective;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 接收历史与head的SQL只在XML维护；所有查询包含完整租户/运行位置/活动键。 */
@Mapper
public interface BundleDirectiveMapper {
    /** 首次不存在时插入，同键并发通过主键仲裁，已存在时保留原序号和时间。 */
    int ensureHead(@Param("key") Key key, @Param("now") String now);
    /** 读取最新锁定值，不能使用等待锁之前的快照。 */
    Long lockHead(@Param("key") Key key);
    /** 精确读取单条历史。 */
    StoredDirective history(@Param("key") Key key, @Param("sequence") long sequence);
    /** 精确读取指令编号已占用的序号。 */
    Long sequenceForId(@Param("key") Key key, @Param("directiveId") String directiveId);
    /** 插入不可覆盖的接收证据。 */
    int save(@Param("key") Key key, @Param("value") StoredDirective value, @Param("recordedBy") String recordedBy);
    /** 空首值和已有序号都使用CAS。 */
    int advance(@Param("key") Key key, @Param("expected") Long expected, @Param("sequence") long sequence, @Param("now") String now);
    /** 在同一查询中读取head引用的完整记录。 */
    StoredDirective latest(@Param("key") Key key);
}
