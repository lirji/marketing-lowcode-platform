# 最新状态：R3 本地机制已交付

2026-09-09。用户在明确 R3 本地范围后再次回复“继续”，批准本地实现。见 [R3 交付报告](R3_DELIVERY_REPORT.md)、[R3 方案](R3_PLAN.md)及 r3-evidence.json。

- R1、R2 VERIFIED 和本轮 R3 本地激活/熔断状态机完成；本轮相关 verify：SPI 93、服务 226 全部通过。
- 更高序号选择可信历史制品、cursor/audit 原子提交、clear 重放不续期、独立 JVM 恢复与坏签名拒绝已验证。
- R3 本地切片 complete；完整发布计划仍受外部依赖阻塞。真实 READY/ACK、权威活动/身份映射、健康水位及参与 epoch 栅栏接线、control 开放尚未完成。
- 既有 CI 覆盖新测试，未运行远程 Actions；无共享迁移/推送/部署。保留其他后端和 Cursor 改动。

以下 R1/R2 记录为上一轮历史证据，后续状态以本节及 R3 报告为准。

---

# 裂变可信发布实施状态

2026-09-09。用户已在审查[跨仓库实施方案](../../../../transaction-center/docs/delivery/referral-release-runtime/DELIVERY_PLAN.md)后回复“继续”，从 R1 连续实施；无需重新批准。

## 当前状态

blocked（外部协议依赖）：R1 与 R2 的 VERIFIED 切片实现、自审、QA 完成；SPI 72、服务 206、架构 JUnit 2 均通过。R2 READY/ACK、R3、R4 仍未完成。

## 已实现

- `referral-runtime-spi`：ReferralReleaseVerifier，受控 Ed25519 双重签名、租户/slot/ABI/摘要、时间及单制品清单校验；严格解析冻结规则，不接受未知/重复/缺失/null 字段或数字强转。
- 签名载荷先复制再验摘要与解析；返回值私有构造、不可变规则和防御性字节副本。没有 READY、活动绑定或许可能力。
- `referral-service`：ReferralReleaseInstallationService + Repository/Mapper/XML；V12 独立制品表，所有列/表有注释。按租户/slot/generation 唯一锁定，完整内容冲突不覆盖，写入失败回滚，锁后按原始精度重验有效期。
- 入口要求 runtime:warm 和冻结 org/shop 范围；是显式内部装配类，没有自动 Bean/HTTP 暴露。
- 默认参与许可及 control 的 REFERRAL_RELEASE_NOT_AVAILABLE 保留。

## 验证记录

- `/tmp/referral-release-r1-compile.log`：编译通过。
- `/tmp/referral-release-r1-test.log`：42 个 R1 真实签名/恶意输入测试通过。
- `/tmp/referral-release-r2-compile.log`：编译通过。
- `/tmp/referral-release-r2-test.log`：最初 WebEnvironment.NONE 导致 HttpSecurity Bean 缺失，8 个测试启动错误，未计为业务通过。
- `/tmp/referral-release-r2-retest.log`：修正 RANDOM_PORT 后 8 项真实 MySQL 专项通过。
- `/tmp/referral-release-regression.log`：补充并发异内容、默认参与拒绝后完整相关回归；最终 SPI 72、服务 206 项通过，无失败/错误/跳过。

## 依赖和阻塞

- `docs/delivery/referral-benefit-assembler/DELIVERY.md` 明确冻结 benefit/SKU 引用为 opaque 字符串，真实映射和来源待签收。
- `BenefitFundingService.assertReleasable(Set<String>)` 校验当前 ACTIVE 权益与 SKU，但不接收冻结 SKU 引用；`BenefitSkuCatalog` 的 long version 不能未经映射就解释上述 opaque 引用。
- 已请求用户指定权威闭包来源/映射。缺失前不建立 READY 事实或签名 ACK；已验证制品可耐久保存，不能声称完整预热通过。
- 编译载荷没有 campaignId，可信活动映射以及熔断新鲜水位也尚未接线。保留 R3/R4 待办，不用请求参数替代权威关系。
- 营销已有前端和 V6–V11 等其他未提交改动原样保留；本次不修改前端或共享服务，不推送/部署。

## CI 和后续

现有 `.github/workflows/ci.yml` 执行全 reactor `clean verify` 并收集 Surefire，能发现新 `*Test` 类，无需再增加重复 job；未运行远程 Actions。

回归、代码自审和报告已完成；本次独立文件按逻辑提交。后续在可信闭包/活动映射/熔断协议落实后接 READY/ACK、激活与许可，再调整 control 发布拒绝。
