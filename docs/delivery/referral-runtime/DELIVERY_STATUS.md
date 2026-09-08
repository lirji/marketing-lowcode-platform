# Delivery Status

## Goal
原平台邀请有礼纯规则 SPI 第一切片；完整裂变、161 项基线与生产门禁不以本切片完成替代。

## State
- Phase: 本阶段实现、13 项专项 QA 与独立代码审查已完成。
- Status: complete
- Last updated: 2026-09-08
- 用户授权并行继续；使用 deliver-feature-end-to-end skill，批准范围来自冻结方案与本次续做指令。

## Completed
- 类型化计划和奖励规则、防御性冻结列表、计划校验、权威三值事实失败关闭。
- 注册/首单资格、退款净额、观察期、窗口/迟到判断与无副作用奖励候选。
- 双边候选独立、阶梯累计、结构化稳定 milestoneKey；不接管持久去重和额度裁决。

## Changed Files
- `runtime-spi/pom.xml`：注册模块。
- `runtime-spi/referral-runtime-spi/pom.xml`：仅 JUnit 测试依赖。
- 模块 `src/main/java/com/acme/marketing/referral/` 下四个公开类型。
- 模块 `src/test/java/com/acme/marketing/referral/ReferralPolicyTest.java`。
- `docs/delivery/referral-runtime/`：方案、QA、自审和执行日志。

## Verification Log
| 命令 | 结果 | 边界 |
|---|---|---|
| `mvn -o -f runtime-spi/referral-runtime-spi/pom.xml test` | PASS，13 tests，0 failures/errors/skipped | 仅纯模块，2026-09-08（含注册窗口修复后复跑），日志 maven-test.log |
| `git diff --check` | PASS | 无空白错误 |

## Decisions And Deviations
不扩语言核心、编译器、服务或前端；现有根 CI `./mvnw ... clean verify` 会通过 reactor 注册纳入本模块。未执行远程 CI 或根全量，未启动服务/数据库，未提交/推送。

## Blockers And Residual Risks
真实权威渠道、业务新客口径、SKU、运营时间与隐私/保留策略仍待确认。纯 Evidence.verified 是可信调用方合同，不是安全边界；服务不得允许客户端直接设置。退款后取消/追回、长期稳定奖励身份/墓碑、风险、配额、事实乱序与持锁重检由后续切片完成。

## Next Action
独立审查已由主 agent 确认注册窗口修复与 13 项证据真实，相关问题关闭；下一阶段依赖型别进入图/编译/控制面与持久 referral 服务，再联调真实权威合同。注册窗口已按主 agent 核对修正为活动开始至绑定时刻，先注册登录再绑定可在权威新客 YES 时达标；未知口径不转为通过。
