package com.acme.marketing.benefit.infrastructure.persistence.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.*;
/** 复核观察永久键与版本CAS；所有写操作均在context锁之后执行。 */
@Mapper
public interface ReferralHeldReviewMapper {
    int reserve(Map<String,Object> values);
    Map<String,Object> lock(@Param("tenant")String tenant,@Param("request")String request);
    Map<String,Object> find(@Param("tenant")String tenant,@Param("request")String request);
    int update(Map<String,Object> values);
}
