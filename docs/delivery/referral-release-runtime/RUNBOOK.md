# 已验证裂变制品的边界与恢复

本轮只增加无副作用验签器和内部耐久安装类。没有公开安装 API，没有生产信任键默认值，也没有 READY/激活或参与许可适配器。不要因为 V12 表有记录就放开 control 的 REFERRAL 拒绝。

- 受控发布/编译公钥交给 ReferralReleaseVerifier；只支持 REFERRAL_PLAN / marketing-referral-plan/1 单计划清单，拒绝未实现灰度和附加制品。URI 仅作为签名清单元数据，不触发网络下载。
- 显式装配 ReferralReleaseInstallationService 时必须使用本服务事务管理器；调用不能已有事务，必须有 runtime:warm、tenant 和冻结组织/店铺权限。返回 VERIFIED 仅表示验签制品已提交。
- V12 增加 mk_referral_verified_release，按 tenant/environment/cell/namespace/generation 唯一。数据库时间 UTC 微秒用于审计；安全判断使用原始 Instant 精度。
- 恢复必须将保存的 manifest_json、release_key_id、artifact_payload 交给当前可信 verifier 重验。密钥撤销、到期、签名或内容不一致不能复用历史安装收据重新开放。
- 同代次异内容返回 REFERRAL_GENERATION_CONFLICT，不能 UPDATE 原制品“修复”。发布新代次；未来回滚也须新激活序号，不能清表或降低 epoch。
- 回滚应用版本时保留 V12 表和审计事实。共享库迁移/生产部署未执行；本轮数据库验证仅专属 Testcontainers MySQL 8.4.11。

冻结 SKU 引用的权威映射与发布闭包、可信 campaign/definition 映射、熔断健康水位和签名 ACK/控制阈值落实前，READY、ACTIVE 和真实参加保持不可用。

## R3 本地指令状态（2026-09-09）

新增显式装配 ReferralRuntimeStateService；仍无 HTTP/自动 Bean。V13 增加 mk_referral_runtime_cursor 与 mk_referral_runtime_directive，按指令流维持单调序号和永久审计。激活使用 runtime:activate，熔断使用 runtime:kill-switch，检查使用 runtime:read；这些是本地应用权限要求，不代表已发布新 HTTP 合同。

激活先从 V12 重验制品，再要求 ReferralRuntimeReadinessPort 的有界证明（最多 10 秒，精确绑定 tenant/manifest/代次/签名）。当前没有生产实现，应传 unavailable()。不得用 VERIFIED 收据构造 READY。

熔断 KILL 的范围只有 tenant/namespace，environment/cell 存储为空，符合现有签名合同。开启后持续拒绝；关闭状态仅在签名 activatedAt 起算的配置窗口（0 < 窗口 ≤ 10 秒）内可通过本地检查。同消息重放不刷新，连续开放仍需要未来可信水位协议，不能按消息消费时间伪造健康。

LocalGuard 只是一次本地检查快照，不能直接转换参与许可。真实入口还需要可信 campaign/definition/scope、身份、READY/ACK 及参与事务内 epoch 栅栏。回滚选择已保存且有效的历史制品，但使用更高 activationSequence；旧序号和同序号异内容都拒绝。保留 V13 两表，禁止通过清表、修改 JSON 或降低游标回滚。
