# Review Report

## Scope
控制面规则校验和纯预览；共享parser机械搬迁。独立初审由 `/root/attribution_review` 于2026-09-08完成，指出中途指纹不等于最终通过。最终指纹及证据为source-evidence.json。

## Findings And Resolution
| 风险/场景 | 处理与证据 |
|---|---|
| control依赖compiler应用包或另写一套业务规则 | parser移至SPI，原代码除package不变；compiler仍持有签名层；机械比较和旧真实payload SHA golden |
| GraphDefinition/Map<String,String>提前绑定导致数字/boolean被转字符串 | controller保存入口先JsonNode，共享原始Map guard；simulate facts用Map<String,Object>，方言判定后校验原值；guard/原JSON测试 |
| 原JSON同名role/facts静默覆盖 | control专属mapper customizer开启STRICT_DUPLICATE_DETECTION；重复role测试 |
| 模拟值被误当权威事实或真实发奖 | 固定simulationOnly=true/evidenceAuthority=SIMULATED_INPUT，候选无awardId/receipt；仅repository读取的交互断言 |
| 校验valid=true误称SKU真实可用 | 返回WARNING REFERRAL_CATALOG_UNVERIFIED，真实目录尚未核验且审批发布关闭 |
| 先前导入manifest绕过stage直接ACK/activate/rollback | 对referral runtime或artifact明确拒绝，三个入口纯测试；submit与decide同样阻断 |
| 旧价格API JSON发生扩字段/空字段 | SimulationResult使用不同record，不给旧价格加referral字段；精确4字段golden |

## Verdict
pass：本阶段50项专项与12项受影响复跑均通过，独立最终复核已通过。12项是复跑子集，不能相加为62项。

## Residual Gates
真实SKU/权益目录的完整不可变引用、权威渠道、policyVersion、发布闭包、规则冻结、持久幂等/配额/授权/履约/补偿、隐私及生产参数仍未完成。旧方言CanonicalGraphHasher历史分隔符风险保留，需独立版本/迁移与签名兼容方案，不能将新方言修复称作旧方言也修好。
