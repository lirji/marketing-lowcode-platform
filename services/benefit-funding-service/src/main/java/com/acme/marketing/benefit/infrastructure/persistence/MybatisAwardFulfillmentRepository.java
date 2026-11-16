package com.acme.marketing.benefit.infrastructure.persistence;

import com.acme.marketing.benefit.application.AwardFulfillmentRepository;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.AwardFulfillmentMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 使用同一营销数据库保存当前观察；不会将远程调用放入数据库事务。 */
@Repository
public class MybatisAwardFulfillmentRepository implements AwardFulfillmentRepository {
    private final AwardFulfillmentMapper mapper;
    /** 构造本服务数据库适配器。 */
    public MybatisAwardFulfillmentRepository(AwardFulfillmentMapper mapper) { this.mapper = mapper; }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public Optional<Intent> findIntent(String tenantId,String intentId) { return Optional.ofNullable(mapper.selectIntent(tenantId,intentId)); }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public void initialize(String tenantId,String intentId,String now) { mapper.initialize(tenantId,intentId,now); }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public void requestRefresh(String tenantId,String intentId,String now) { mapper.refresh(tenantId,intentId,now); }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public List<Candidate> due(String now,int tenantLimit,int limit) { return mapper.due(now,tenantLimit,limit); }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public boolean claim(Lease lease) { return mapper.claim(lease)==1; }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public boolean complete(Completion completion) { return mapper.complete(completion)==1; }
    /** 继承端口约束，返回实际数据库仲裁结果。 */
    @Override public Optional<Stored> find(String tenantId,String intentId) { return Optional.ofNullable(mapper.select(tenantId,intentId)); }
}
