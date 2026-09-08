# 受保护永久准备仓储交付记录

## 范围

新增V10表与专用Mapper/仓储、固定AAD候选保护Port/Service（默认拒绝）、DATETIME微秒+纳秒余数Codec。未修改V1–V9、旧Drools仓储或abandon路径。库中只含业务身份/摘要/受保护候选、租约、receipt/rejection和本地意图引用；不保存原主体、原token或候选明文，不设置未确认的清理期限。

保护服务在事务外调用AEAD并以相同完整Identity AAD回验；恢复再次认证解密并重算原payload hash。随机密文不同不影响同身份永久回放，唯一冲突不会覆盖原密文。仓储写操作要求现有事务，当前读取得行锁后读取Clock，执行封闭领域命令并以完整Identity、行版本、旧fence/owner CAS更新；receipt异内容返回且持久化粘性隔离，不能以异常回滚隔离。

## 当前证据

- 独立源码预审无阻断，允许执行隔离数据库专项。
- 11:42:28离线纯组合12项通过（保护4+状态机8）；11:43:46新增无DB Mapper XML解析后受影响保护类5项通过。当前唯一纯覆盖13项，不能将12+5重复相加。
- 新数据库专项8方法已编译。11:45启动唯一临时mysql:8.4.11容器，300秒单次启动预算，tmpfs；没有应用服务、workers或真实渠道。
- 首跑13350在Hikari连接池初始化失败：测试将无查询参数的TC URL拼接&connectionTimeZone，尚未执行Flyway/场景。已改用Hikari属性（仅测试修复），失败日志maven-mysql-first-failure.txt保留。重跑33971于11:54:49 +08:00退出0，8项数据库专项通过，失败/错误/跳过均0。V1–V10共10迁移成功；临时容器已清理。详见maven-mysql.txt。
- 当前唯一专项覆盖21项：13纯+8数据库，不重复累计首跑错误或纯测子集。Surefire报告已拷回本目录。
- 12个相关源码/状态依赖SHA与实际最终测试快照一致，见source-evidence.json。
- 独立最终审查已通过（12源码SHA及21专项均核对）；当前只能称此仓储专项通过，不是完整生产验收。

## 不在本片内

真实confirm恢复必须同时检查currentState/currentRevision，CANCEL_REQUESTED下原receipt只供恢复/对账，不能直接成为新受理或投递许可。当前仓储保存旧纯模型，取消上下文分支仍待下一服务编排。永久准备状态不等于实际权益成功；后续应用服务必须把receipt、本地intent、Outbox和expected-fact同事务保存。本片无HTTP/实际confirm/风控/Outbox发送；真实来源、密钥、权限、生产参数、隐私方案仍待确认。

## 数据库实际验证边界

专用MySQL类手动装配Hikari/DataSourceTransactionManager/SqlSessionTemplate，Flyway运行本模块完整V1–V10，不启动应用服务或旧relay。8项覆盖表/列中文注释、随机重加密原密文不变、全Identity拒绝覆盖、raw纳秒租约/旧fence、同确认ID不同纳秒内容持久隔离、确认后本地事务rollback保原CONFIRMING并同身份恢复、双线程首次准备唯一、performance_schema证明真实行锁等待后纳秒截止拒绝。最后一例还校验异常确为领域时效/状态拒绝而非其他SQL失败。

本片数据库验证只覆盖准备仓储，示例ACCEPTED仅保存准备表中的本地意图引用，并未插入真实可投递意图；旧mk_award_intent_outbox保持空。真实服务上线必须在同一短事务保存实际receipt/intent/Outbox/expected-fact并校验当前取消状态，不能直接把仓储引用当作端到端成功。
