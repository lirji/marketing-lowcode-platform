package com.acme.marketing.decision.infrastructure.persistence.mapper;

import com.acme.marketing.decision.application.DecisionInventoryRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL 由本站 Mapper XML 按完整物理位置执行。 */
@Mapper
public interface DecisionInventoryMapper extends DecisionInventoryRepository {
    @Override void ensureHead(@Param("slot") Slot slot);
    @Override Position lockHead(@Param("slot") Slot slot);
    @Override Position current(@Param("slot") Slot slot);
    @Override void append(@Param("slot") Slot slot, @Param("position") Position position, @Param("json") String json,
            @Param("actor") String actor, @Param("now") String now);
    @Override int advance(@Param("slot") Slot slot, @Param("previous") long previous, @Param("position") Position position);
}
