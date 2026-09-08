package com.acme.marketing.benefit.infrastructure.persistence.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 永久准备SQL仅放XML；没有删除或更新冻结候选的入口。 */
@Mapper
public interface ReferralPreparationMapper {
    int reserve(Map<String,Object> values);
    Map<String,Object> lock(@Param("tenant")String tenant,@Param("request")String request);
    Map<String,Object> find(@Param("tenant")String tenant,@Param("request")String request);
    int compareAndSet(Map<String,Object> values);
}
