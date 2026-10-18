package com.acme.marketing.jobs.journey.persistence;

import org.apache.ibatis.annotations.Param;

/** 运行时只读映射；所有查询都绑定租户与槽位，SQL 放在同名 XML。 */
public interface JourneyRuntimeMapper {
    /** 读取指定租户的紧急开关。 */
    KillRow killSwitch(@Param("tenantId") String tenantId, @Param("namespace") String namespace);

    /** 新入组必须绑定当前期望版本。 */
    CurrentRow current(@Param("tenantId") String tenantId, @Param("environment") String environment,
            @Param("cell") String cell, @Param("namespace") String namespace);

    /** 已入组只读取指定历史激活记录，避免被后续发布换版。 */
    RuntimeRow activated(@Param("tenantId") String tenantId, @Param("environment") String environment,
            @Param("cell") String cell, @Param("namespace") String namespace,
            @Param("generation") long generation, @Param("sequence") long sequence, @Param("artifactId") String artifactId);

    record KillRow(boolean enabled, String reason) { }
    record CurrentRow(long generation, long activationSequence, String artifactId) { }
    record RuntimeRow(long generation, long activationSequence, String directiveJson,
            String releaseKeyId, String manifestJson, byte[] payload, String artifactId) {
        public RuntimeRow { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
