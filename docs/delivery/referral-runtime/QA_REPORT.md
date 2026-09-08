# QA Report

## Environment Profile
本地原平台 main 工作树，Java 21.0.11 / Maven 3.9.12；无外部依赖/服务。已有前端修改保留。仅测试当前新增 SPI，不声称原平台全量或交易当前工作树全量通过。

## Cases
| 验收 | 场景 | 证据/实际结果 |
|---|---|---|
| AC-01 | 固定 quantity=1、逐人门槛、邀请人阶梯、额度声明、版本必填、重复 ID、不可变列表/时间溢出 | ReferralPolicyTest 配置与不可变性测试通过 |
| AC-02 | 观察期前及精确成熟边界、半开绑定/资格窗口、未来与空证据、权威 UNKNOWN/NO、退款净额门槛/溢出/非法值、币种不符、注册时间（含先接收后绑定、观察期起点不重写）、结算截止、迟到/重放 | 同测试类资格测试通过 |
| AC-03 | 双边逐人+3/5阶梯累计、人数下降、同输入候选稳定、不可变结果、非法人数 | 候选测试通过；不代表持久不重发已实现 |
| AC-04 | 离线模块 Maven test | 13 tests，0 failures/errors/skipped，BUILD SUCCESS；maven-test.log |

## Defects And Retests
自审发现注册后登录再绑定被错误排除，已按冻结权威新客口径修复并完成 13 项复跑；详见 REVIEW_REPORT。

## Automated Regression
`mvn -o -f runtime-spi/referral-runtime-spi/pom.xml test`，未执行根 reactor 全量。现有 `.github/workflows/ci.yml:28` 的根 verify 将包含已注册模块，但远程 CI 尚未运行。

## Blocked External Checks
会员/订单权威渠道、Graph→编译签名→runtime ACK、持久资格/配额/授权/履约、退款/补偿、隐私以及生产准入均未在本切片实现或验收。

## Verdict
pass：纯 SPI 13 项专项与独立审查完成；集成/生产门禁仍未完成。
