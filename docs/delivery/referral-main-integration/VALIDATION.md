# 裂变分支 main 集成验证

2026-09-09。用户要求将本轮相关分支合并到各仓库远程 main；通过独立工作树集成，保留原工作区的 Cursor/其他未提交改动。

## 分支与依赖

- 交易：feat/console-integration-delivery，来源 d85d392，8 个待合入提交。
- 营销：feat/referral-completion，来源 ba93fd8，3 个发布机制提交。
- 权益：feat/referral-sku-version，来源 30e4331，1 个 SKU 版本受理提交。
- 远程同步后，其余相关旧分支的提交已包含在对应 main 中。

营销提交已带 V12/V13，但 V6–V11 与后端配套尚在原工作区。因此按 `docs/delivery/referral-completion/evidence/20260908-final/source-sha256.json` 核对并复制全部 53 个后端源文件，指纹完全一致，再补成独立提交。没有复制 Cursor 前端或其他未确认源文件。此前报告中“未提交”是历史状态，以本次集成记录为准。

## 验证与修复

- 交易干净提交：`mvn -o -q -DskipTests package` 成功；本次未重复其历史全量数据库/E2E 验收。
- 权益干净提交：AwardItemIntentTest、AwardIntentHasherTest、SkuVersionPlanningTest 共 14 项通过。
- 营销首轮扩大 verify 发现已有故障：三个 @Repository 实现是 final，Spring CGLIB 无法启动；旧测试把迁移总数写死为 11，而实际已有 V12。
- 修复三个仓储的代理兼容性，保持异常转换和事务配置；测试改为精确断言 V11 成功应用，不再限制未来迁移总数。
- 修复后 BenefitFundingIntegrationTest 15、ReferralAwardIntakeMySqlTest 8、ReferralHeldReviewMySqlTest 7、ReferralPreparationMySqlTest 8 共 38 项通过，无失败/错误/跳过。
- 最终针对本轮影响范围的 reactor verify 结果见 validation.json。不是整仓库所有测试均通过的声明。

## 集成边界

前置裂变后端与活动类型/接口接线分批提交；已有代理/测试缺陷单独修复提交。main 使用正常快进合并与非强制推送，不重写历史。

未运行共享数据库迁移、部署或真实发奖。READY/ACK、权威主体/活动/SKU 映射与发布开放仍保留原门禁。远程 CI 由 push 触发，本地验证不等于远程 CI 已通过。

原工作区仍保留原分支和改动。特别是营销 Cursor 前端、导航文档以及权益 .gitignore 未混入推送；已补提交的后端在原工作区仍按原样保留，后续切换分支应先对照 main，避免重复提交。
