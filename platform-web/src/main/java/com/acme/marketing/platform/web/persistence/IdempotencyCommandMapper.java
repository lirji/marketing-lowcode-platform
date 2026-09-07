package com.acme.marketing.platform.web.persistence;

import org.apache.ibatis.annotations.Param;

/**
 * 通用 API 幂等表 MyBatis Mapper。
 *
 * <p>各领域库使用相同的 mk_api_command 表契约，SQL 统一维护在平台 Mapper XML 中。
 */
public interface IdempotencyCommandMapper {

    /** 首次请求尝试创建处理中记录。 */
    int insertIgnore(CommandWrite command);

    /** 加锁读取幂等记录。 */
    CommandRow selectForUpdate(@Param("tenantId") String tenantId,
            @Param("operationName") String operationName, @Param("idempotencyKey") String idempotencyKey);

    /** 删除已过期的幂等记录。 */
    int delete(@Param("tenantId") String tenantId, @Param("operationName") String operationName,
            @Param("idempotencyKey") String idempotencyKey);

    /** 在已确认过期后重新创建处理中记录。 */
    int insert(CommandWrite command);

    /** 保存首次成功响应。 */
    int complete(@Param("tenantId") String tenantId, @Param("operationName") String operationName,
            @Param("idempotencyKey") String idempotencyKey, @Param("responseJson") String responseJson,
            @Param("updatedAt") String updatedAt);

    /** 幂等命令写模型。 */
    record CommandWrite(String tenantId, String operationName, String idempotencyKey, String payloadHash,
            String stateName, String responseJson, String createdAt, String updatedAt, String expiresAt) { }

    /** 幂等命令读模型。 */
    record CommandRow(String payloadHash, String stateName, String responseJson, String expiresAt) { }
}
