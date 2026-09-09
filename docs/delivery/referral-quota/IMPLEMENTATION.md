# 永久奖励、数量配额与授权回执

## 当前实现

V6奖励账本与资格/进度/Outbox同事务。稳定身份由tenant、campaign、beneficiary HMAC、role、ruleId、relation/threshold规范里程碑组成，不含规则发布版本、资格修订或重试时间。双边分别一份，阶梯累计追加。永久失效身份不因再次达标重建；技术PENDING仅阻止新授权，不伪装为永久不达标。受益人密文保留原参与者/关系上下文，未直接解密/复制为新的AAD。

V7数量配额总账户从已固定奖励规则读取上限，显式 `initialize(scope,rewardId,bucketCount)` 初始化；权限`referral:quota-configure`，桶数1–1024，重复不重置余额，改变规则或桶数拒绝。热路径`reserve`要求`referral:quota`，同事务串行个人限额、桶、reservation、reward与Outbox。桶由participant稳定散列选择，规则跨桶共用个人限额。空闲桶不足返回WAIT_QUOTA，不能宣布全活动售罄；冷路径transfer只调拨available并推进双epoch。没有自动配额分配器/定时器或公开配额写HTTP。

真实当前证据水位或任务水位领先资格时，新预占返回WAIT_AUTHORITY。该检查不是线上授权：预占之后新事实仍可到达，必须继续走V8最终锁定复检。

资格明确失效时，未授权且从未提交的奖励可在同事务返还reserved与个人占用，并永久保存RELEASED。已确认/UNKNOWN等保留占用及PENDING补偿，不能根据超时、租约或404释放。V10已接内部可信终态持久化及consumed推进；真实渠道适配未完成，不能把RESERVED当作到账。

V8永久授权receipt：事务外可信ProofPort验证完整候选/固定奖励/风险与运行许可；默认返回null拒绝。锁序为anchor共享锁→participant→qualification/progress→按resource排序的current→task→reward→receipt/Outbox。新证据、待投影请求、资格失效、未预占、过期任一情况拒绝首次确认。保存后再按原始Instant复检，过期回滚全部写入。回执以秒/纳秒保存首次确认时间，重试返回原confirmationId、sourceRequestId及原稳定摘要；当前取消水位独立返回。

`Result.dispatchAllowed`始终为false：这个内部回执不是发送许可。真实HTTP响应签名/身份适配、benefit HELD复核到实际发送、终态和取消栅栏仍待接通。恢复原确认不依赖旧token仍有效，但验证调用机器权限、租户/组织/门店及稳定摘要。

## 配置与性能边界

- `REFERRAL_AUTHORIZATION_MAX_DEPENDENCIES`：默认0禁用首次确认；明确配置1–10000。阶梯逐项锁当前依赖，超过预算拒绝，不无界加载。该上界不是生产容量验收；超大热点活动仍需设计/验证更高效的依赖汇总与准入。
- `REFERRAL_AUTHORIZATION_MAX_PROOF_SECONDS`：默认0禁用；三个可信许可窗口都必须在此范围内，且最终SQL完成时仍有效。
- 无真实ProofPort时，即使配置非零也不能首次确认。
- V9仅补活动资源seek索引；运营列表真实主库读取，不提供跨页/全局事件一致性承诺。

## 未完成与外部依赖

自动配额调拨/公平任务调度、可信候选签发、外部回执签名、benefit实际投递与最终成功/追回、外部终态订阅与对账、发布治理及运行时、measurement、C端/BFF、种子及真实前端联调仍未完成。身份/组织/门店权威来源、绑定年龄起点、KMS、SKU/风控/权益渠道与生产参数尚未确认。所有本次迁移只在Testcontainers隔离MySQL执行，没有共享或生产部署。


## V10可信履约事实与运营补齐

终态ProofPort事务外认证完整奖励身份、固定受益人/SKU/稳定来源号，默认拒绝。许可寿命`REFERRAL_FULFILLMENT_MAX_PROOF_SECONDS`默认0关闭。入箱在参与者→奖励→个人配额→桶锁下原子提交版本历史、Inbox、奖励、配额及Outbox；没有来自浏览器的自报成功入口。

最高累计版本只前进；原事件/原版本异摘要拒绝，老终态也必须符合当前累计成功/未发放/追回事实。首次成功保存永久successDigest；追回后仍允许原成功重放，但绝不抹掉成功或返还默认政策下的consumed。确认未发放才释放；UNKNOWN/ACCEPTED保留reserved。已失效的成功为PENDING补偿；后续资格失效不得覆盖REVERSED/CANCELLED/MANUAL_REVIEW。

V10仅为能证明本地未发送取消的旧RELEASED行补齐terminalFact，其他异常历史不伪造外部证明。所有新增DDL有表/列注释。发布迁移前仍需专门升级演练；本轮在隔离空库自动应用V1–V10。

运营summary在单SQL内聚合真实投影，eventWatermark=null；类型STANDARD/REFERRAL由control V4持久化。合同见OPERATIONS_API和CAMPAIGN_TYPE_API。


V11受控复评：权限/原因/永久幂等键与队列、审计Outbox同事务；只排队，不能修改资格或发奖结果。逐人重算原关系，阶梯全参与关系有界排队，超出拒绝。最终验证与剩余整体工作见 `../referral-completion/DELIVERY_REPORT.md`。
