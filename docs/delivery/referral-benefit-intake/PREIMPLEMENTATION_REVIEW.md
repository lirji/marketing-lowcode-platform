# Benefit 裂变授权受理：实施前独立审查

2026-09-08。依据冻结 10-FINAL_PLAN §6.3、§12 及 09-PRODUCTION_DESIGN 的取消/授权竞争与可靠受理约束，核对当前 `services/benefit-funding-service` 和 `marketing-contracts/referral`。本文件不构成真实来源、权益中心、风控或生产签收，未运行测试、未改生产代码。

## 当前代码与可复用边界

现有服务是 benefit-funding-service，不是新建 benefit-service。`AwardIntentAssembler.SOURCE_SYSTEM` 固定 `drools-activity`，`AwardIntentService` 的 claim、去重、block、查询、完成和 expected-fact 均沿用它。旧 Assembler 验证 OfferToken、查已生效 benefit 与 SKU，然后组装旧来源意图。不能把这一常量改成 marketing-referral，也不能用裂变凭证伪造 OfferToken 进入旧路径。

`AwardIntentRepository` 多数方法已显式接受 sourceSystem，可在审查 SQL 全链路隔离后复用底层结构；旧 Service 不能直接复用其硬编码编排。`AwardDispatchModeRouter` 默认 LEGACY/SHADOW，只有显式租户覆盖进入 CENTER。新裂变入口必须检查 CENTER；其他模式拒绝，不能沿用旧路径 `SENT/LEGACY_OWNED/SHADOW_RECORDED` 作为裂变成功。

`AwardIntentService` 已有短事务租约→事务外组装/风控→短事务保存结果的参考流程，但其首次风控不可用会固定 block。裂变要求暂时不可用保留同 reward 的待评估尝试，不能机械复用为永久终结，也不能改变旧首次结果语义。

## 最小组装合同

新增专用 `ReferralAwardIntentAssembler`，只接受验签后的 ReferralAwardAuthorizationClaims、历史发布证明及冻结规则/目录结果。来源固定 marketing-referral；没有调用方可选 sourceSystem、subject、SKU、quantity 或现金字段。

逐项核对 tenant、issuer、专用 audience、组织/店铺、reward/sourceRequestId、participant、relation 或 milestone、role、ruleId、definition/version/generation、artifactId/hash、qualificationRevision。`ReferralAwardIdentity` 的 sourceRequestId 由精确 tenant+永久 reward 身份产生；重签只改变 key/time，业务声明摘要不同必须冲突。

从已经验证的历史制品中按 ruleId 定位唯一奖励，验证 role/关系或阶梯/quantity=1，按冻结 benefitDefinitionVersion 与 skuVersion 重建一个固定 COUPON SKU。不能查询“当前最新版本”后当作原冻结内容；活动发布证明、artifact attestation、ABI、原规则 hash、目录 SKU 类型/版本任一缺失或不一致均失败关闭。当前目录能否证明不可变 SKU 版本需专用 Port 明确，不把旧 requireActiveSku 直接宣称为冻结版本校验。真实目录尚未签收。

受益人必须与授权声明及可信规则一致，保持 Unicode 精确值；不是机器 JWT sub。主体密文/HMAC用途与外部权益中心必要投递载荷须分别治理，不能把旧普通 subjectHash 当作新隐私合同已达标。公开响应、审计、错误不返回原 token 或主体。

### 权益中心受理锁下版本前置

补充只读核对：benefit-center 已有 AwardItem.skuVersion、查询输出及 BenefitCatalogRepository.findSkuVersion；不重复建设查询或补偿。当前 AwardItemIntent 没有 expectedSkuVersion，AwardApplicationService.plan 在受理事务内使用 templateCache.findCurrent。营销侧先读目录匹配，无法保证权益中心随后受理时仍是同一版本，不能静默添加一个接收后不使用的版本字段。

建议下一独立小切片增加可选 expectedSkuVersion：非空时走专用锁定 Port，在同一受理事务锁当前 bc_benefit_sku，验证 tenant/sku、当前 version==expected、ACTIVE 和时间窗口，以锁定行生成计划及保存 AwardItem.skuVersion；缺省保留旧路径。多个 SKU 按稳定 tenant+sku 顺序预锁，避免不同请求项目顺序导致死锁。版本字段必须进入规范请求摘要，缺省时保持原摘要字节；受理成功的同键重放先按原凭证返回，不重新依赖当前目录。单独读历史版本不能代替当前 ACTIVE 门禁。

权益中心技术事实：根 pom 为 Spring Boot 3.3.5；benefit-adapters/pom.xml 使用 starter-jdbc；当前无 MyBatis/Mapper XML 执行配置，资源目录仅有 Flyway。JdbcCatalogRepository.findSku/findSkuVersion 是旧内联 JDBC，findSkuVersion 查询历史表后可按相同版本回退当前行，无锁。BenefitCenterConfiguration 使用同 DataSource 的 JdbcTemplate 和 TransactionTemplate/SpringUnitOfWork。

在“新 SQL 仅 Mapper XML/Flyway”约束下，推荐仅增加 mybatis core+mybatis-spring，手动配置一个 SqlSessionFactoryBean 与专用 MapperFactoryBean 共用既有 DataSource/事务管理器，新增 SkuAcceptanceMapper XML；不要为这一操作迁移全部旧 JDBC，也不要借用营销平台 Boot4 MyBatis starter。现有本地依赖可见 mybatis 3.5.16、mybatis-spring 3.0.3；正式锁定组合须在权益中心 Boot3 构建专项验证。只加锁读无需新增业务表。生产端到端 SKU 冻结验收仍需两端显式版本映射及并发变更/停用/缓存陈旧反例。

## 安全受理流程

1. 新 Service 默认关闭、拒绝外层事务。先验证专属机器身份、权限、租户/组织/店铺范围和 CENTER；网络地址/issuer/key/算法来源固定配置，不能来自 token 头或业务 body。
2. 原成功回放先按已认证调用方、tenant、固定 sourceSystem、sourceRequestId、稳定奖励身份/摘要查永久回执。已有成功可在 token 过期/旧钥退休后回放，但不得靠未验签声明或 sourceRequestId 一项就向其他主体返回原意图。后续实现必须明确可信 relay 身份与永久回执核对方式；纯 codec 本身不完成该授权。
3. 新请求在事务外验签、历史制品/目录读取、风险评估。真实网络/KMS 调用使用有限超时、结果水位和固定来源。得到的是经过验证的候选，尚未受理，不返回发奖成功。
4. 本地短事务建立永久来源作用域及可恢复准备记录/租约，保存稳定声明摘要、固定组装摘要、资格 revision 和必要受保护证据；提交后才调用 referral 的 `confirm-authorization`。并发请求只允许合法 owner 执行，接管使用 CAS/fencing；不能持数据库锁等远程确认。
5. 事务外按相同 reward/sourceRequestId/qualificationRevision/稳定声明摘要确认资格。referral 必须在自己的 reward 锁内串行化取消与授权消费，并永久重放原确认。网络超时表示 UNKNOWN，不能视为确认成功或确认失败，不重新生成外部幂等身份。
6. 本地短事务锁定来源准备记录，校验 owner/fencing、完整摘要、CENTER 与有效许可；首次受理在锁后复检授权时间。将可信确认 receipt、永久成功引用、固定意图 Outbox、必要 expected-fact 和审计原子提交。确认 receipt 的字段、来源、时效与幂等语义需单独可信合同，不能只接受 boolean=true。
7. 若远端已确认而本地提交失败/进程崩溃，保留准备记录并用同身份重查/重放远端确认；不得放弃记录、创建第二奖励或重新消费。旧确认恢复与“首次短期 token 已过期”的边界必须显式实现：用永久确认凭证恢复同一操作，不能重新解释成新授权。未获得可信确认前 Outbox 不得可投递。

以上不是跨库原子事务。永久准备、远端幂等确认、本地 receipt+Outbox 原子提交用于恢复中间状态；不能把一次 HTTP 200 当作跨服务原子完成证明。在线资格确认和权益中心发券是两个不同边界。

## 取消、未知与来源隔离

- referral 取消先于确认：确认返回失效/revision 冲突，不建可投递意图；同 sourceRequestId 不得通过改 revision/new key 绕过。
- 确认先于取消：进入后续取消/补偿流程；取消墓碑及对账保留，不能以签名过期假定在途权益没有发出。
- 风控 UNKNOWN/UNAVAILABLE/REVIEW/CHALLENGE：保留可恢复状态或可信待处理案件，不授予资格。REJECT 不通过新 sourceRequestId 绕过；旧 OfferToken 路径行为不变。
- relay 接到202只表示耐久受理；超时/未知保持恢复查询。只有可信履约终态能记成功；取消请求已发送不等于已撤回。
- sourceSystem 必须贯穿 dedupe、attempt/block、receipt、intent、outbox、查询、expected-fact、回调关联和取消。新来源到权益中心的白名单及 `referral:` 幂等 Header 合同待确认；不能仅在新 payload 增字段而复用旧来源查询。

## 下一实施最小范围与门禁

第一步仅在 benefit-funding-service 新增纯 `ReferralAwardIntentAssembler`、历史证明/冻结 SKU/裂变风险/资格确认 Port 及类型、拒绝默认配置和纯合同测试。实际受理 Service 在上述可信结果合同明确后新增独立 `ReferralAwardIntakeService` 与永久准备/确认 Repository/Mapper；新迁移号按届时目录确认（当前已有 V9），不修改旧迁移。可复用旧不可变意图 DTO，但不复用会注入 drools-activity 的旧组装入口。

后续新增 internal Controller 必须单独确认只读/写权限、固定机器来源、CENTER 和严格 additionalProperties:false；不进入公网网关路由。底层共享仓储与 relay 若增加新来源，须同时回归旧来源全链路，禁止悄然开放实际投递。真实默认 Ports 继续拒绝，真实 HTTP/JWKS/KMS/权益中心/风险客户端不在纯合同切片中虚构实现。

验证至少覆盖：两来源同外部键隔离；同 reward 重签/过期后成功回放；跨主体/tenant/Scope/稳定摘要冲突；错误历史制品/版本/SKU/现金拒绝；CENTER 拒绝边界；所有外部调用实际在事务外；锁等待过期；并发单确认/租约接管；远端确认成功但本地回滚恢复；确认超时 UNKNOWN；取消先后顺序；receipt/Outbox/expected-fact 原子回滚；202不计成功；旧 OfferToken/LEGACY/SHADOW 首次风控语义保持。以隔离专项和独立审查收口，不把本地替身测试写成生产验收。
