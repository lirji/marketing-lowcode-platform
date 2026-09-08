# 配额桶纯守恒边界

依冻结09-PRODUCTION_DESIGN §6.4及05-DATABASE_DESIGN D12-D14，仅实现活动数量配额桶的纯算术/状态边界，不作为权益库存或资金预算，不开DB/HTTP/调度。

桶身份固定tenant/campaign/rule/bucket，allocated=available+reserved+consumed且非负。所有变更要求匹配完整身份、fencingEpoch与rowVersion；热路径预占1仅移动available→reserved。没有可用量返回WAIT_QUOTA，不能凭单桶缺额宣称全活动售罄。预占账本身份绑定reward，已RESERVED原回放不再减量；CONSUMED/RELEASED永久保留，不能重用原reward再次预占。真实全库reward唯一、participant个人限额和桶/账本/reward/Outbox同事务仍须后续仓储实施，纯类不声称已防数据库并发超发。

可信履约成功reserved→consumed；网络UNKNOWN/202/查询404/租约或时间到期不产生任何释放转换。明确未发或撤销成功可释放reserved；已经consumed的历史权益默认不返还，只有受信冻结政策明确允许且可信追回成功才能返还。终态重放不重复移动计数。

冷路径两桶调拨只移available；匹配tenant/campaign/rule、不同桶、两个当前epoch/version，原子返回双桶新状态，两侧epoch及version递增，旧worker不可继续修改。不得凭本类返回对象声称已经跨行原子持久化；总账户上限/初始分桶/锁序另实现。无固定桶数、等待时间或生产参数默认值。

测试关注守恒、重放不二次占用、未知不释放、终态不可复用、陈旧fence拒绝、跨租户转移拒绝与长序列算术安全；完成后独立审查。
