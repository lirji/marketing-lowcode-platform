# 订单证据到资格任务的持久分页派发

依V4A已审账本与资格DELIVERY_PLAN，事实事务仅原子写current/history/Inbox/fanout，不扫描全部关系。下一派发器复用V4 fanout，领取/分页提交使用owner+fence+rowVersion+desiredVersion，所有租约/页大小显式配置，默认不自动调度。

候选关系为当前证据HMAC/索引版本/原Scope匹配的绑定关系，UNION所有已有资格订单依赖；按relationId去重、稳定游标分页。Scope漂移或隔离必须仍覆盖原订单依赖。BOUND事务独立enqueue补“证据先到、关系后绑定”竞态。队列任务只表示重评，不判资格、不直接改人数或发奖。

锁顺序：fanout派发事务仅fanout→资格任务，不获取participant/relation/current写锁；资格实际处理事务participant→relation/qualification→current→任务完成CAS，避免task→current与事实current→fanout→task构成锁环。读取候选关系为普通一致性读；新证据在current→fanout提交时重置游标/fence，旧worker完成CAS不得抹消新水位。领取短事务先提交，不把远程工作放入锁中；本派发器本身没有KMS/网络调用。

V5资格任务/依赖表由资格切片统一建表，此处只定义派发合同与独立Mapper/Service，待任务接口稳定后接入。首次验证覆盖翻页、旧owner/fence、目标水位推进、分页enqueue原子回滚、已有依赖Scope漂移、迟绑定补任务，不宣称完整资格/奖励完成。

## V5接口与隐式锁决策

已与资格作者明确：`application.qualification.ReferralProjectionEnqueuePort.enqueue(Signal)`，Signal为tenant/participant/relation/resourceId/desiredVersion/reason；BOUND只能null/0，证据触发必须非空/正修订。V5 task主键tenant+relation，requested_revision按该关系单调推进，不能比较不同订单revision的最大值。`mk_referral_qualification`提供tenant_id/relation_id/participant_id/evidence_resource_id供依赖UNION。

任务表不增加relation/participant/current外键，避免INSERT时隐式父行锁闭合上述死锁环；不是忽略引用完整性。BOUND在持有原关系锁的事务内写队列；fanout两条查询均JOIN真实永久关系，并核tenant/participant/关系匹配，内部Port不对HTTP暴露。关系不可删除/改绑，未来任何数据清理方案必须重新审查此引用和锁协议。任务消费者也需核当前关系，缺失进入受控修复而非编造资格；这一选择需在V5迁移说明与测试里保留。
