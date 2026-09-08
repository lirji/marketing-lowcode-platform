# 首绑交付状态

2026-09-08。首绑实现、12个唯一纯场景及11项真实MySQL专项通过，生产结构及最终报告/源码指纹均已独立复核通过。没有执行共享迁移、清理、提交或推送。

## 实现

- ReferralBindingService/Timing、BindingPermit、Repository、Relation与默认拒绝配置；maxBindAge起点仍未确认，绝对bindUntil由可信历史许可Port承接，不猜token或参与者创建时间。
- V3 relation/bind_command两张全中文注释表；永久tenant+campaign+invitee唯一归因和按完整业务摘要的命令回执，HMAC字典序角色锁，固定原参与版本/条款。分享token可用于多个好友，不做单次消费。
- TokenBindingReadPort通过现有token Repository/Mapper提供定位和事务内锁定。原成功同key在token/许可过期或撤销后仍回放；新关系及新key回执必须锁后复检。所有外部身份/历史许可获取在事务外。
- 关系初始BOUND/PENDING_EVIDENCE，同事务关联角色、审计及内部Outbox；未触发资格成功或奖励。原始Instant裁决时限，仅落库boundAt/deadline向下截断微秒。

## 验证与证据

- 纯测试8+新增3+纳秒1，共12个唯一场景，日志build-unit-retest.log、build-unit-edge.log、build-unit-nanosecond.log。不存在无依据重复全量。
- 隔离DB11通过：session 8820退出0，11测试、0失败/错误/跳过，build-db-retest.log；报告封存evidence/db-11。
- evidence/source-digests.json含18个本切片源码/测试/接线文件，全部与通过DB11的隔离快照相同。
- evidence/dependency-snapshot-digests.json单列24个既有依赖文件；其中旧ParticipationService及referral-service/pom与当前不同：前者已由另一专项修纳秒时限，后者因下一V4A新增SPI依赖。DB11没有冒称覆盖这两项后续改动；绑定核心无变更，不因此重复DB。
- 初轮session97754仅在MySQL初始化超时，尚未执行业务用例，主动结束后退出1。build-db-startup-timeout.log和线程证据保留。只将专用测试fixture改单次300秒并与根任务错峰，未放宽业务断言；未重启Docker或清理其他容器。

## 后续

最终独立复核报告与18文件SHA通过，本切片已收口。资格V4计划已独立审查，事务外证据准备边界正在独立纯专项，真实Inbox/历史/current/fanout和资格投影尚待实现。真实BFF/JWKS/KMS、永久jti、历史签名发布/条款、maxBindAge起点、隐私留存/物理清理及生产参数继续待确认；此结果不是全工作树或生产验收。
