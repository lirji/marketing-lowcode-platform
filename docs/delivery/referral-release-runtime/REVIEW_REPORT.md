# 裂变发布 R1 / R2 VERIFIED 代码审查

2026-09-09。由同一 Agent 分阶段自审，不宣称独立审查。范围为本轮新增 verifier、安装服务、仓储、V12 和测试；未覆盖既有 Cursor/其他未提交工作。

## 已处理发现

| 级别 | 场景与证据 | 处理 |
| --- | --- | --- |
| 中 | 安装服务只核对 runtime:warm 与 tenant 时，受限 org/shop 的调用者可能安装范围外制品；ReferralReleaseInstallationService.install | 增加已验证计划的 requireOrganization/requireShop；加入范围拒绝测试 |
| 中 | 解析器若容忍缺字段、null、重复字段或数字强转，签名载荷可能在不同消费者解释不同；ReferralReleaseVerifier.json | 独立严格 JsonMapper，并用有效签名覆盖 18 种非法计划 |
| 中 | 原始 byte[] 可能在摘要校验和反序列化间被修改；ReferralReleaseVerifier.verifyInstallation | 进入边界后先复制，摘要/解析/结果使用同一快照；输出也复制 |

后二项在实现时已防御，本报告记录复核场景，不声称曾有已上线漏洞。

## 复核结论

- Mapper 无覆写制品内容的 SQL；ON DUPLICATE KEY 仅保留主键值，随后 FOR UPDATE 读原始事实。异常不吞，提交完成后才返回安装收据。
- Manifest 比较使用解析后 record 相等，不依赖 JSON Map 字段顺序；字节必须完全一致。
- 时间首次和锁后验证，过期成功历史不能续期；verified_at 是审计时间，不是新的有效期。
- VERIFIED 独立于 READY/ACTIVE，无假 ACK、配置开关绕过或参与许可副作用。
- 外部 SKU 权威映射未定不阻止存储已验制品，但阻止可信预热就绪；不是可忽略的中低风险。

切片 verdict：pass。SPI 72、referral 服务 206 和架构 JUnit 2 均通过；R2–R4 完整发布仍受依赖阻塞。
