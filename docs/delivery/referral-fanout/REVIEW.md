# 持久扇出独立审查

日期：2026-09-08。只读源码、日志及快照核验，未运行测试/容器，未改实现。

## 结论

当前七文件源码审查无新增阻断；8项纯测试原始Surefire报告为 tests=8 / failures=0 / errors=0 / skipped=0。**V5实际SQL执行、UNION分页以及真实任务/游标事务原子性仍待联合MySQL验证**。此结论不是扇出全链路、资格、发奖或生产验收通过。

## 已核边界

- 默认条件Bean不开启，无scheduler；lease/page size均要求显式正配置，1000仅单页安全上限。内部入口要求referral:project并拒绝外层事务。
- claim以SKIP LOCKED领取单租户候选并先提交；dispatch重新锁定完整Task，校验owner/fence/rowVersion/desiredVersion与Claim一致。
- current非锁定读取，必须与fanout目标水位匹配。派发仅fanout→task，不显式锁current/participant/relation。
- V5任务不建父外键是经审查的锁协议选择。绑定事务内创建受控引用；UNION两支返回真实永久relation，依赖支JOIN包括tenant/relation/participant。未来消费者缺关系须修复，禁止以此选择允许删除/改绑。
- 页大小+1判更多数据，按relationId稳定去重/游标；每个目标复检租户和组织/店铺权限。Signal区分BOUND与证据触发；不同订单的修订不能直接作关系任务水位大小比较。
- enqueue与finish同事务；新证据重置fence/cursor后旧Claim不能完成。租约用原始Instant在enqueue后、finish后复检；V4截止向下截微秒仅会提前失效，不延长有效期。
- SQL更新不改变fanout父引用字段；部署需继续保持V5 task无父FK，防止首次插入隐式关系锁形成资格→current→fanout→relation环。

## 测试证据的实际含义

8项纯用例覆盖分页/游标、旧owner与fence、enqueue等待过期、finish后过期、当前证据水位不符、跨租户及Scope/乱序目标、租约精度和未配置/外层事务。使用替身仓储及事务管理器，证明控制逻辑和回滚请求，不能证明MySQL真实锁及提交。

`maven-pure.txt`采用安静日志，仅包含Mockito/JVM告警，没有BUILD SUCCESS文本；通过依据是实际快照里的Surefire XML，不能虚构日志成功行。未重新执行任何测试。

快照：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/referral-fanout-jvcnwz2l`。
报告：快照内 `services/referral-service/target/surefire-reports/TEST-com.acme.marketing.referral.ReferralEvidenceFanoutTest.xml`。

## 指纹

以下当前文件与上述实际测试快照逐字节相同：

| 文件 | SHA-256 |
| --- | --- |
| `services/referral-service/src/main/java/com/acme/marketing/referral/application/fanout/ReferralFanoutRepository.java` | `6d828ad34b695cf5aece922305fc3db96b5baf2a2d4fb61548401b3225a37dff` |
| `services/referral-service/src/main/java/com/acme/marketing/referral/application/fanout/ReferralEvidenceFanoutService.java` | `d35e9b7e5ec2263d430e5bfc79255a84c23abb3234a4036649ff2f9da4d297bc` |
| `services/referral-service/src/main/java/com/acme/marketing/referral/application/qualification/ReferralProjectionEnqueuePort.java` | `326895fcdfac3172fc0abd17fc487fe0a476f9566c5d67f9d01ad40e8c9d9789` |
| `services/referral-service/src/main/java/com/acme/marketing/referral/infrastructure/persistence/MybatisReferralFanoutRepository.java` | `ec49547157fadfedba093803d52e7815f46756be390cc1bacd6eb597ae2572ab` |
| `services/referral-service/src/main/java/com/acme/marketing/referral/infrastructure/persistence/mapper/ReferralFanoutMapper.java` | `957f5441c3ae398c584b074608e5f29d433a0498ed02fdd510092d33f4485509` |
| `services/referral-service/src/main/resources/mapper/ReferralFanoutMapper.xml` | `2418bd76c501eeedaf0415a7c5fd3207e05fc5bfb77d6aeb1e12dd18e7bdeba7` |
| `services/referral-service/src/test/java/com/acme/marketing/referral/ReferralEvidenceFanoutTest.java` | `0bf3efc341e9b2cd55828bce15fd2f5d0d28df8e8d57101e7b5aa04f7d7c9fbf` |

## 联合DB必须保留的检查

V5表落盘后验证：当前Scope与历史依赖UNION去重/多页、BOUND迟绑定补任务、两订单触发同relation独立requestedRevision、新证据推进时旧游标失效、过期claim接管、队列故障导致页游标和所有enqueue一起回滚，以及三事务并发时无隐式FK锁环。数据库阶段仍未通过，不与纯8重复累加或声称实际队列已验证。
