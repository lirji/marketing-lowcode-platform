# Review Report

## Scope
只新增SPI模型、纯合并器与测试；不修改交易中心真实累计账本、不触碰referral-service/绑定或现有事件入口。

| 来源/严重度 | 问题 | 修复与证据 |
|---|---|---|
| 独立审查/高 | 同scope可信较新revision金额/状态不一致只返回REJECTED+旧READY，随后投影/旧重放仍可verified=true | invalidTrusted对>=当前修订做quarantine；非法金额、币种、REFUNDED金额场景与旧重放测试 |
| 自审+root方案/高 | 仅latest无法识别历史revision的不同内容 | 强制显式HistoryLookup；Seen对scope/revision/内容比较；旧revision冲突回归，无生产三参重载 |
| 自审/高 | 更新revision历史不可用后被旧revision重放清成READY | historyPendingRevision水位；旧重放不得清更新水位，同等/更新完整证据核验后才恢复 |
| 独立审查/中 | Scope默认record输出递归泄露canonicalSubject | 覆盖Scope.toString脱敏，Snapshot/Observation/State/MergeResult均有不泄露断言 |
| 独立审查/中 | 主体缺少C01 Unicode约束 | 256码点、拒孤立代理项、256 emoji允许；不作trim/NFC/casefold |

## Contract Clarifications
同revision不完整退款通知与完整查询内容不同仍冲突，需要统一完整快照/新修订或后续独立补充合同。运输时间/标识变化不算业务内容变化。查重Unavailable必须失败关闭；历史已有但State缺失/落后时不重新用本次重放时间初始化锚点。quarantine永不被新事件自动解开。

## Verdict
conditional-pass：30项专项已过，独立最终复核待确认。

## Residual Risks
sourceVerified/mappingConfirmed均是受信调用者合同，不是密码学证明。真实来源验签、来源/租户权限、主体映射、会员新客关联、数据库历史查重/Inbox原子性和冲突人工恢复尚未实现。State不是完整审计库，历史由外部关系库保存；不设置未确认的数据删除/保留期或生产额度。
