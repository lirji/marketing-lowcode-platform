# 受控复评合同

POST `/api/v1/referral-rewards/{rewardId}:reevaluate`，权限`referral:reevaluate`，头`Idempotency-Key`为8–128位字母数字及`._:-`。Body为`{"reason":"操作原因"}`，原因必填且最多128字符，去除首尾空白后参与幂等摘要。租户、组织、门店和操作者来自认证上下文。

返回202：`{operationId,rewardId,state:"QUEUED",relationCount,createdAt}`。只表示同事务永久登记并排队，不表示完成、发奖或修改了资格。相同操作者相同键和内容返回原收据，不再次排队；同键异奖励或异原因拒绝。跨租户/组织/门店不能请求复评。原因存于操作表和审计，不进入公开事件载荷。

逐人奖励只重算原关系；阶梯奖励重算该参与者所有已绑定关系。单次上界`marketing.referral.reevaluation.max-relations`默认1000、允许1–1000；超过整笔拒绝，不会悄悄只复评前1000人。大活动异步分页复评尚未实现。

复评沿用V5任务和可信资格来源，不修改永久失效身份，不退回已消耗配额，不绕过发前风险/授权，不改变受益人、SKU或金额。完整任务处理仍需要现有内部worker调用/调度及真实PermitPort；尚未配置时可停在排队或PENDING，不能显示成功。

V11增加永久命令表并扩展无订单触发原因。MySQL8的V5自动命名第三检查约束被替换为命名约束；其他数据库不适用。迁移在隔离MySQL验证，未操作共享库。队列、收据、审计与Outbox原子提交，任何后续写失败全部回滚。
