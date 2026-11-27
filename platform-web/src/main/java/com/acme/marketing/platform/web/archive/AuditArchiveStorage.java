package com.acme.marketing.platform.web.archive;

import java.util.List;

/** 对象归档端口将业务范围与独立存储角色的请求分开。 */
public interface AuditArchiveStorage {
    String putOnce(String key, byte[] bytes, String sha256);

    byte[] verify(String key, String version, int bytes);

    byte[] recover(String key, String version, int bytes);

    /** 按恢复凭证前缀逐页清点，分页标记不解释为业务数据。 */
    Inventory inventory(String prefix, String continuation, int limit);

    /** 获取清点对象当前发布的精确版本、长度和摘要。 */
    ObjectReference current(String key);

    /** 有界的一页对象键及续查位置。 */
    record Inventory(List<String> keys, String continuation) {}

    /** 可由恢复身份独立回读的凭证引用。 */
    record ObjectReference(String version, int bytes, String sha256) {}
}
