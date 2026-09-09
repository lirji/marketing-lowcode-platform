# 资格账本切片状态

2026-09-08。V4A事实账本主要实现已完成，准备边界9个纯场景、Intake应用10个唯一纯场景通过；root独立编写的真实MySQL11已通过，V4A共30个唯一场景；最终独立复核已关闭，19 当前源/依赖 SHA 与 DB 快照匹配。V4B/V5 资格投影、人数及任务正在实施，尚未验证，不代表完整资格或奖励交付。

## 已完成实现

- ReferralEvidencePreparation复用SPI merger；固定tenant/source/order身份，完整历史revision/存在性/摘要及聚合版本CAS。Scope/HMAC漂移保留原State永久隔离；原始Instant判断许可，maximumLifetime必须显式受信配置，无生产默认。
- TrustedReferralEvidencePort、ProtectedReferralEvidencePort、ReferralEvidenceIntakeService与默认拒绝装配。认证/加解密/merger准备全部事务外；短事务仅锚点→Inbox→单order current→history锁后复检，CAS变化有界返回事务外重试。
- 永久Inbox同event同内容返回原结果且不解密current；异内容跨订单只隔离原授权资源，提交隔离/审计后再返回受控冲突，原回执不变。原Scope授权不等于允许访问漂移后的新组织/店铺。
- MybatisReferralEvidenceRepository、Mapper/XML和V4四表current/history/inbox/fanout。所有表/字段中文注释；NO PAD二进制字符排序；history初收另存epoch秒/纳秒；State完整加密含原锚点，quarantined同时受Header AAD保护。
- current/history/Inbox及待投影fanout/内部Outbox同一短事务。没有直接更新qualification/progress/reward，也不启动默认Worker或真实来源订阅。

## 专项证据

- prepare9及独立终审通过，evidence/prepare-9有报告/日志/3SHA；旧pom与后续root依赖变化分开，不追改历史证据。
- build-intake-unit.log首次编译仅-Werror提示内部RetryPreparation缺serialVersionUID，补齐后build-intake-unit-retest.log应用9项通过。
- 独立审查发现Inbox冲突隔离不应依赖businessDigest变化。已改按原Header.quarantined安全状态写入，build-intake-quarantine-digest.log定向2项通过（新增同digest反例+原跨订单场景复跑），应用共10个唯一场景，不能把9+2当11唯一场景。
- 应用纯测试内存保护替身只验证调用/CAS/时限，不是密码学或真实KMS验收。root独立ReferralEvidenceMySqlTest使用真实AES-GCM+独立HMAC的测试保护实现，隔离DB11全部通过（80699退出0、0失败/跳过）；evidence/db-11有原报告和19项源码/依赖SHA全匹配。真实KMS仍未接通。
- 当前无本Agent数据库任务。root/runtime的DB窗口错峰，禁止同时拉起重型测试容器，不重启共享Docker或清理他人数据。

## 下一步

1. V4A 独立终审已关闭；source-evidence.json 精确汇总 prepare9+Intake10+DB11=30 唯一场景。
2. 历史 V4A 与 Binding18 SHA、报告继续保留；V4B 仅对新 BOUND 仓储接线做后续独立验证，不追改旧证据或共享数据库。
3. 继续V4B单关系资格/人数/持久投影任务与fencing，复用当前账本水位；BOUND补查及quarantine按既有订单依赖fanout仍属于后续实现，不能把仅有持久待办当作投影已执行。

root在同service/pom追加marketing-contracts依赖用于独立奖励纯边界，并补application.yml运行配置；后续复制必须保留，不覆盖其修改。真实身份/来源/JWKS/KMS/签名规则、会员与风险口径、隐私保留/清理、maxBindAge起点及生产参数继续待确认，无公开入口、实际部署或奖励调用。

## V4B/V5 当前增量（不追改 V4A 验收范围）

已新增资格三表、永久会员水位/冲突隔离、注册首次接收摘要锚、精确观察期任务、valid/ever 原子计数、审计/Outbox和 BOUND 同事务入队。只开放内部逐关系处理方法，Permit 默认不可用，租约/重试/许可最大寿命配置默认 0 拒绝；没有 Worker、HTTP、真实会员/首单/KMS来源或奖励调用。

纯计数 8 项及纯应用 18 项通过（26 唯一；evidence/v4b-count-8、evidence/v5-application-18）。应用包含事务外来源/解密、完整冻结 Scope、锁后及保存后过期、旧 fence/CAS、会员修订回退/同版冲突、注册首次接收锚、观察期纳秒、退款 valid 撤回和 ever 保留。真实 V5 DDL/行锁/原子回滚及 root fanout 联合 DB 专项待验证；当前工作树并非全量通过。

V5 生产结构独立复核已通过：保存后时限和会员/注册锚问题均关闭，无待先修阻断。已同步 member_revision 中文注释为“已知最高、仅首次无许可为零”，等待 root 联合隔离 MySQL；未运行共享迁移。

## 2026-09-08 恢复实施

用户明确“先把裂变做完”，沿用已批准资格/奖励设计继续。工作分支 `feat/referral-completion`；保留 Cursor 的未提交前端改动。已新增 `ReferralQualificationMySqlTest`，正在隔离 MySQL 验证 V5 与 fanout，不依赖已删除的 long-task 技能。当前不宣称专项或全量已通过。

后续顺序：V5/fanout 数据库缺口及修复 → 奖励永久账本/配额/授权与追回 → 发布及查询接口 → 回归、CI和Cursor交接。真实权威来源、绑定期限起点、测试SKU/渠道仍待提供，内部可独立实现工作持续推进。
