package com.acme.marketing.decision.infrastructure.persistence.mapper;

import com.acme.marketing.decision.application.BundleAdmissionRepository.Key;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Slot;
import java.util.List;
import com.acme.marketing.decision.application.BundleApplicationRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 事务门禁与有界回执查询的 SQL 统一由 XML 维护。 */
@Mapper
public interface BundleApplicationMapper extends BundleApplicationRepository {
    @Override int serialize(@Param("key") Key key);
    @Override Long current(@Param("key") Key key);
    @Override String lockApplication(@Param("key") Key key);
    @Override Long lockPointer(@Param("key") Key key);
    @Override Inventory inventory(@Param("slot") Slot slot);
    @Override void attest(@Param("key") Key key, @Param("namespace") String namespace, @Param("sequence") long sequence, @Param("deployment") long deployment, @Param("instance") String instance, @Param("json") String json);
    @Override String receipt(@Param("key") Key key, @Param("namespace") String namespace, @Param("sequence") long sequence,
            @Param("deployment") long deployment, @Param("instance") String instance);
    @Override List<String> receipts(@Param("key") Key key, @Param("namespace") String namespace, @Param("sequence") long sequence, @Param("deployment") long deployment);
    @Override boolean previouslyApplied(@Param("key") Key key, @Param("bundleId") String bundleId, @Param("version") long version, @Param("hash") String hash);
    @Override int append(@Param("key") Key key, @Param("sequence") long sequence, @Param("directiveId") String directiveId, @Param("json") String json, @Param("evidence") String evidence, @Param("now") String now);
    @Override int first(@Param("key") Key key, @Param("sequence") long sequence, @Param("now") String now);
    @Override int advance(@Param("key") Key key, @Param("previous") long previous, @Param("sequence") long sequence, @Param("now") String now);
    @Override Boolean protectedSlot(@Param("key") Key key, @Param("namespace") String namespace);
    @Override int protectSlot(@Param("key") Key key, @Param("namespace") String namespace);
}
