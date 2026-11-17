# 活动组合发布与上游接入增量设计

## 已批准决定与边界
- 2026-11-17 用户在比较 C 端体验与风控后确认继续：同一活动的资格、优惠、权益与 Journey 采用固定组合版本，整体生效及整体回滚。准备不齐继续有效旧组合；旧组合也不可用时暂停新参与。已有参与固定原版本，实时风险、停用、同意和额度检查仍执行。已发生的外部效果由补偿处理。
- 会员/画像的优先来源为 commerce-platform，裂变交易事实优先复用 transaction-center，认证保留 auth-platform。此决定不表示接口、机器凭据或跨系统主体映射已经接通；订单事实只能来自订单实际发生的系统。
- 本次在现有营销仓库增量实现，沿用 MySQL、MyBatis Mapper XML、ControlCommandExecutor、签名清单与审计链。无换栈、新服务、分布式事务或新中间件；无生产部署授权。

## 事实与依赖
- F1：控制面目前逐 slot 暂存/ACK/激活。slot=(tenant,environment,cell,runtime,namespace)。已有 generation/activationSequence CAS 和签名 ActivationDirective；它们不能证明跨 slot 的请求版本一致。
- F2：ReleaseManifest 固定制品、定义版本、源摘要及审批引用。decision/journey 对制品闭包已有独立校验；referral 发布仍默认拒绝。
- F3：Commerce 已实现会员、等级、标签和行为事实，其成交画像来自自身订单。当前可复用接口以员工/会员操作为主；营销用机器批量读取、稳定全量快照和合并/撤销合同仍需补齐，不能用一次分页扫描冒充同一时点快照。
- F4：交易中心真实 POST /internal/v1/order-referral-evidence:query 接收 orderIds；首单补查已独立为 /internal/v1/first-order-referral-evidence:query，接收 memberId/from/to/firstOrderPolicyVersion。权威主体映射 UnavailableSubjectMapping 仍返回 Pending；首次确认支付与营销目标合同的结算口径不能直接改名等同。
- F5：当前运行中为营销及必要依赖，其他应用保持停止。源码合同测试不替代真实上游运行、生产身份、容量或灾备验收。

## 组合协议与一致性
- Control 拥有不可变组合版本及整体激活指针；每个成员仍引用已校验的签名 ReleaseManifest，不另造制品来源。一个组合只属于一个 tenant/campaign/environment/cell，成员按 slot 唯一。
- 先交付不可激活的 PREPARING 组合：只有完整事务提交后可查，内容不可修改；修改组合创建新版本。受此组合管理的具体清单禁止再由单清单入口激活或回滚。
- 后续运行端准备必须绑定 bundleId/contentHash/精确成员版本，并提供当前有效的就绪证据；不能把旧单清单 ACK 当作组合能力证明。
- 真正启用前须完成组合签名、运行端保留旧新版本、入口固定组合版本、跨服务传递与一致性校验，以及旧入口/同 slot 替换的完整防绕过。仅数据库同时更新 slot 不作为最终完成标准。
- 新参与绑定确定的组合，后续动作沿用该绑定。缺失或不一致的成员不得回退到各自 latest。正常回滚产生单调递增的激活序号并指向曾生效且仍有效的完整组合；不能复活被撤销的风险/资格许可。
- 定时激活采用绝对 UTC 时点，展示 Asia/Shanghai；到期重新检查审批、制品、来源、就绪与版本条件。持久化目标和结果、CAS 防重；调度接入沿用个人 XXL-JOB 约束，实际组件接入在对应切片再核对。

## S11e 具体契约：不可变组合准备
- POST /api/v1/campaigns/{campaignId}/release-bundles；release:write、当前活动组织/店铺范围、Idempotency-Key。body={expectedVersion,manifestIds}，expectedVersion>=0，成员2–8个、不重复。规范化排序后参与幂等摘要，活动与命令身份纳入作用域。
- 所有成员必须是当前租户、当前活动、同 environment/cell 的未过期 STAGED 清单；runtime/namespace 不重复；不允许部分灰度组合。成员引用必须存在真实定义与已批准版本，不能信任客户端另传 campaign 或 slot。
- 返回201 {bundleId,campaignId,version,environment,cell,state:"PREPARING",contentHash,members:[ReleaseManifest],createdBy,createdAt}。不提供激活入口，不产生 ActivationDirective/Outbox；PREPARING 不等于运行端 READY。
- GET 同路径 /latest 和 /versions/{version}：release:read、当前活动范围；未找到404。写命令重放前重新检查当前权限/活动范围，撤权后不能读取旧成功回执。
- expectedVersion 与最新组合版本比较，冲突409；相同键相同规范请求重放原结果，异载荷409；成员已属于另一组合409。本片每个新组合使用新暂存清单，可复用不可变制品，但不共享同一清单登记。
- 同事务保存组合、成员索引与原审计链。以已有 campaign 行锁和release_bundle_version分配游标串行化版本生成，以成员清单的固定顺序行锁及唯一键仲裁并发归属；失败全部回滚。
- 单清单 activate/rollback 的新执行及幂等回放均校验成员归属，拒绝 RELEASE_BUNDLE_REQUIRED。执行中的检查使用清单行锁保护，与组合登记竞争只能一个成功。
- 迁移 V8：mk_release_bundle（tenant+bundle 主键、tenant+campaign+version唯一）；既有mk_release_manifest增加可空bundle_id及租户组合外键/索引。归属直接放在已锁定清单行，避免空成员索引的间隙锁导致无关活动并发登记死锁。campaign增加默认0的组合版本分配游标，避免空历史范围的FOR UPDATE间隙锁；每列及表中文注释，索引支持精确活动版本读取。首片不执行线上迁移、不提供删除历史版本入口。

## 实施顺序与验收
| ID | 可观察结果 | 依赖 | 关键验收 | 当前状态 |
|---|---|---|---|---|
| S11e | API可创建/恢复不可变组合准备版本，具体成员不能单独激活 | 当前审批、清单、控制库 | 真实MySQL并发版本/成员归属、幂等异载荷、跨租户/范围、审计回滚、无发布副作用、旧单清单兼容 | VERIFIED_LOCAL |
| S11f | 签名组合及运行端按组合准备、精确版本读取 | S11e | 伪造/过期/乱序/缺成员拒绝，旧新版本共存；实际运行端能力证明 | TODO |
| S11g | 业务入口绑定组合，整体激活/回滚及全入口防绕过 | S11f | 进程故障/并发发布时无新旧混用，旧参与固定版本，风险停用即时生效 | TODO |
| S11h | 控制台完成组合创建、就绪、整体操作及恢复反馈 | S11g | 真实API、桌面/手机、错误保留和乱序回执保护 | TODO |
| S11i | 持久定时激活及暂停/恢复 | S11g | 到期重验、重启接管、超时查询原结果、过期不自动发布 | TODO |
| S06b-source | Commerce机器读取/快照和主体映射合同及适配 | 来源方向已确定；上游合同/凭据待具备 | 真实来源、完整快照、水位、撤销及跨租户验证 | DEPENDENCY_PENDING |

S11e 允许变更：control应用/接口/Mapper/迁移及测试、marketing-api机器合同、当前交付文档。其余模块在后续切片明确范围后才改。所有片串行处理共同合同与迁移链。该表细化原 S11，不替代原 S06–S13 总体目标。

## 验证与回退
- 首片使用 Testcontainers 独占 MySQL；不连接或修改共享业务数据。回归已有发布/审批、合同和架构检查；测试不能以单纯Mock替代并发与事务验证。
- V8为扩展迁移，既有API/签名载荷保持兼容。新组合登记后必须保留新版本控制服务的防绕过能力；不能回退到不识别组合归属的旧控制服务并恢复写入。运行回退应先冻结发布写入口，保留新增数据。
- 首片不宣称跨运行端原子发布已交付；最终业务完成以S11g/h和相关真实链路验收为准。

## S11f 细化：签名准备证据

S11f 按同一任务内的三个可验证增量实施：f1 控制面导出完整签名组合；f2 Decision/Journey核对已持久化的精确成员后返回组合专属签名ACK；f3 控制面收集ACK并按每成员当前有效的副本/容量门禁计算就绪。全部完成前保持PREPARING，任何一个阶段都不激活流量。

- f1：`GET /api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/manifest`，要求当前release:read和活动范围，返回`ReleaseBundleManifest`。包含tenantId、bundleId、campaignId、version、environment、cell、原contentHash、完整members、createdAt、expiresAt、signatureKeyId、signature。expiresAt为最早成员过期时间；已过期拒绝。签名有独立协议用途前缀，并覆盖所有成员完整规范字段及其签名，不能仅签成员ID。原准备版本/摘要不变；换签名钥匙只改变外层签名，不创建业务新版本。该证据证明冻结内容来源，不代表就绪或激活。
- f2：新增runtime:warm保护的两个组合准备入口。先验证可信租户、控制面签名、时间和本站slot，再从本服务持久库读取该成员的精确generation，核对原清单签名及制品字节，并复用现有编译签名/ABI/结构校验。仅返回组合绑定ACK，不改变active slot，也不依赖旧ACK或进程内缓存证明持久化。
- 现有generation表已持久保存不可变清单和制品，无自动删除；本片复用它们作为准备恢复来源，不新增第二份制品副本或临时组合数据库。后续请求固定版本读取使用相同精确寻址；将来新增清理任务必须引用参与生命周期，不能直接删除保留成员。
- 组合ACK封装现有RuntimeAck并额外绑定bundleId、campaignId、version、contentHash和精确成员签名，外层签名有独立用途前缀。验证须同时通过内外层签名，旧单清单ACK不能补字段冒充新协议。ACK只证明签发时已安装，需要在激活时重新校验新鲜度和每个成员的容量。
- f1允许变更：marketing-contracts签名合同/测试，control组合导出入口/测试，机器API及本设计。f2再扩展Decision/Journey各自运行端及测试；f3涉及控制面ACK持久化和迁移。无新服务、中间件、后台线程或在线控制面回源。
- 验收：改任意成员或外层字段、换租户、过期、未知密钥、混用旧ACK均拒绝；换序规范化稳定；历史准备读不变；权限撤销后导出拒绝。运行端需证明重启后从持久库核验、未安装/字节不符拒绝、旧新generation同时存在、active指针未变。
