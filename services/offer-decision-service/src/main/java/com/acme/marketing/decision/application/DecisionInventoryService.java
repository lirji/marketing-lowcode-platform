package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.release.DecisionDeploymentInventory;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Position;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Slot;
import com.acme.marketing.decision.deployment.DecisionInventoryJson;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import com.acme.marketing.platform.error.ForbiddenException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.time.SqlTime;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 登记流水线声明的位置和内容，业务应用必须另行收齐对应版本的运行证据。 */
@Service
public class DecisionInventoryService {
    public static final String PUBLISH = "deployment:publish";
    private static final int TRANSACTION_SECONDS = 10;
    private static final Position INITIAL = new Position(0, "");
    private final DecisionInventoryRepository repository;
    private final Map<String, PublicKey> keys;
    private final Slot slot;
    private final String pipelineTenant;
    private final String pipelineActor;
    private final Clock clock;

    public DecisionInventoryService(DecisionInventoryRepository repository, Clock clock,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell,
            @Value("${marketing.runtime.namespace:main}") String namespace,
            @Value("${marketing.deployment-inventory.trusted-public-keys:}") String trust,
            @Value("${marketing.deployment-inventory.tenant:}") String tenant,
            @Value("${marketing.deployment-inventory.actor:}") String actor) {
        this.repository = repository; this.clock = clock; this.slot = new Slot(environment, cell, namespace);
        this.keys = TrustedPublicKeys.parse("", "", trust); this.pipelineTenant = tenant; this.pipelineActor = actor;
        if (tenant.isBlank() != actor.isBlank() || tenant.isBlank() != keys.isEmpty())
            throw new IllegalArgumentException(ReleaseBundleProtocolError.DECISION_PIPELINE_IDENTITY.message());
    }

    /** 物理位置由本服务配置确定；仅显式指定的认证机器身份可访问该跨租户清单。 */
    public TenantScope requirePipeline() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission(PUBLISH);
        if (!pipelineTenant.equals(scope.tenantId().value()) || !pipelineActor.equals(scope.actorId()))
            throw new ForbiddenException(ReleaseBundleProtocolError.DECISION_PIPELINE_IDENTITY.code(),
                    ReleaseBundleProtocolError.DECISION_PIPELINE_IDENTITY.message());
        return scope;
    }

    /** 过期后也可查询最新登记位置，流水线据此更新下一版。 */
    public Position current() {
        requirePipeline();
        Position position = repository.current(slot);
        return position == null ? INITIAL : position;
    }

    /** 同序号同内容重放返回既有位置；争抢下一序号的不同内容只能有一个提交成功。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_SECONDS)
    public Position record(DecisionDeploymentInventory inventory) {
        var scope = requirePipeline();
        inventory.requireAuthentic(keys, slot.environment(), slot.cell(), slot.namespace(), clock.instant());
        repository.ensureHead(slot);
        Position previous = repository.lockHead(slot);
        var now = clock.instant();
        inventory.requireAuthentic(keys, slot.environment(), slot.cell(), slot.namespace(), now);
        var next = new Position(inventory.sequence(), inventory.contentDigest());
        if (next.equals(previous)) return previous;
        if (previous.sequence() == Long.MAX_VALUE || next.sequence() != previous.sequence() + 1)
            throw ReleaseBundleProtocolError.DECISION_INVENTORY_SEQUENCE.conflict();
        repository.append(slot, next, DecisionInventoryJson.write(inventory), scope.actorId(), SqlTime.format(now));
        if (repository.advance(slot, previous.sequence(), next) != 1)
            throw ReleaseBundleProtocolError.DECISION_INVENTORY_SEQUENCE.conflict();
        return next;
    }
}
