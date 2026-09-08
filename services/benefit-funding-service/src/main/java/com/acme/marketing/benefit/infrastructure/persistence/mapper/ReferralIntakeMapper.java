package com.acme.marketing.benefit.infrastructure.persistence.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 新来源HELD专用SQL，不接旧relay选择器。 */
@Mapper
public interface ReferralIntakeMapper {
    int reserve(Map<String,Object> values);
    Map<String,Object> lock(@Param("tenant")String tenant,@Param("request")String request);
    Map<String,Object> find(@Param("tenant")String tenant,@Param("request")String request);
    int observation(Map<String,Object> values);
    int risk(Map<String,Object> values);
    int accepted(Map<String,Object> values);
    int heldOutbox(Map<String,Object> values);
    int expectedFact(Map<String,Object> values);
    int cancelHeld(Map<String,Object> values);
    int cancelExpected(Map<String,Object> values);
}
