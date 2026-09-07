package com.acme.marketing.decision.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 决策服务数据库 Mapper；SQL 统一维护在同名 XML 中。 */
@Mapper
public interface DecisionMapper {

    /** 删除单个已过期幂等记录。 */
    int deleteExpiredCommand(@Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey, @Param("expiresAt") String expiresAt);

    /** 插入幂等记录。 */
    int insertCommand(CommandWrite command);

    /** 加锁读取幂等记录。 */
    CommandRow selectCommandForUpdate(@Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 完成幂等请求。 */
    int completeCommand(@Param("tenantId") String tenantId, @Param("idempotencyKey") String idempotencyKey,
            @Param("responseJson") String responseJson);

    /** 插入运行时 generation。 */
    int insertRuntimeGeneration(RuntimeGenerationWrite generation);

    /** 插入运行时槽位初始记录。 */
    int insertRuntimeSlot(RuntimeSlotWrite slot);

    /** 加锁读取运行时槽位。 */
    SlotPointerRow selectRuntimeSlotForUpdate(RuntimeSlotKey slot);

    /** 读取运行时槽位。 */
    SlotPointerRow selectRuntimeSlot(RuntimeSlotKey slot);

    /** 更新运行时槽位期望指针。 */
    int updateRuntimeSlot(RuntimeSlotWrite slot);

    /** 查询某个物理槽位的全部租户期望指针。 */
    List<DesiredPointerRow> selectDesiredPointers(RuntimeSlotQuery slot);

    /** 查询指定运行时 generation。 */
    RuntimeGenerationRow selectRuntimeGeneration(RuntimeGenerationKey generation);

    /** 仅在版本更新时更新受众成员投影。 */
    int updateAudienceMembershipIfNewer(AudienceMembershipWrite membership);

    /** 插入受众成员投影。 */
    int insertAudienceMembership(AudienceMembershipWrite membership);

    /** 查询水位之后变化的受众成员投影。 */
    List<AudienceMembershipRow> selectAudienceMembershipsChangedSince(@Param("updatedSince") String updatedSince);

    /** 加锁读取紧急开关。 */
    KillSwitchStateRow selectKillSwitchForUpdate(@Param("tenantId") String tenantId,
            @Param("namespace") String namespace);

    /** 插入紧急开关。 */
    int insertKillSwitch(KillSwitchWrite killSwitch);

    /** 更新紧急开关。 */
    int updateKillSwitch(KillSwitchWrite killSwitch);

    /** 查询命名空间中的全部紧急开关指令。 */
    List<KillSwitchDirectiveRow> selectKillSwitchDirectives(@Param("namespace") String namespace);

    /** 幂等记录写模型。 */
    record CommandWrite(String tenantId, String idempotencyKey, String payloadHash, String stateName,
            String responseJson, String createdAt, String expiresAt) { }

    /** 幂等记录读模型。 */
    record CommandRow(String payloadHash, String stateName, String responseJson) { }

    /** 运行时 generation 写模型。 */
    record RuntimeGenerationWrite(String tenantId, String environmentName, String cellId, String runtimeName,
            String namespaceName, long generationNo, String releaseKeyId, String manifestJson,
            byte[] artifactPayload, String manifestSignature, String warmedAt) {
        public RuntimeGenerationWrite {
            artifactPayload = artifactPayload.clone();
        }

        @Override
        public byte[] artifactPayload() {
            return artifactPayload.clone();
        }
    }

    /** 运行时 generation 唯一键。 */
    record RuntimeGenerationKey(String tenantId, String environmentName, String cellId,
            String namespaceName, long generationNo) { }

    /** 运行时 generation 读模型。 */
    record RuntimeGenerationRow(String releaseKeyId, String manifestJson, byte[] artifactPayload) {
        public RuntimeGenerationRow {
            artifactPayload = artifactPayload.clone();
        }

        @Override
        public byte[] artifactPayload() {
            return artifactPayload.clone();
        }
    }

    /** 带租户的完整运行时槽位键。 */
    record RuntimeSlotKey(String tenantId, String environmentName, String cellId,
            String runtimeName, String namespaceName) { }

    /** 不带租户的运行时槽位查询条件。 */
    record RuntimeSlotQuery(String environmentName, String cellId, String namespaceName) { }

    /** 运行时槽位写模型。 */
    record RuntimeSlotWrite(String tenantId, String environmentName, String cellId, String runtimeName,
            String namespaceName, long desiredGeneration, long activationSequence,
            String directiveSignature, String directiveJson, String updatedAt) { }

    /** 运行时槽位指针读模型。 */
    record SlotPointerRow(long desiredGeneration, long activationSequence,
            String directiveSignature, String directiveJson) { }

    /** 租户期望 generation 指针。 */
    record DesiredPointerRow(String tenantId, long desiredGeneration, long activationSequence) { }

    /** 受众成员投影写模型。 */
    record AudienceMembershipWrite(String tenantId, String audienceId, String subjectHash, boolean memberValue,
            long membershipVersion, String expiresAt, String updatedAt) { }

    /** 受众成员投影读模型。 */
    record AudienceMembershipRow(String tenantId, String audienceId, String subjectHash, boolean memberValue,
            long membershipVersion, String expiresAt, String updatedAt) { }

    /** 紧急开关写模型。 */
    record KillSwitchWrite(String tenantId, String namespaceName, long switchSequence, boolean enabledValue,
            String reasonText, String directiveSignature, String directiveJson, String updatedAt) { }

    /** 紧急开关加锁读模型。 */
    record KillSwitchStateRow(long switchSequence, String directiveSignature,
            boolean enabledValue, String reasonText) { }

    /** 紧急开关缓存重建读模型。 */
    record KillSwitchDirectiveRow(String tenantId, String directiveJson) { }
}
