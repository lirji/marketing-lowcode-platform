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
| S11e | API可创建/恢复不可变组合准备版本，具体成员不能单独激活 | 当前审批、清单、控制库 | 真实MySQL并发版本/成员归属、幂等异载荷、跨租户/范围、审计回滚、无发布副作用、旧单清单兼容 | DELIVERED 0b332bf |
| S11f | 签名组合及运行端按组合准备、精确版本读取 | S11e | 伪造/过期/乱序/缺成员拒绝，旧新版本共存；实际运行端能力证明 | DELIVERABLE，CI44108641742 PASS |
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

### S11f2 接口与持久化复核
- POST `/api/v1/decisions/runtime/release-bundles:prepare`、`/api/v1/journey-runtime/release-bundles:prepare`；`runtime:warm`，body={bundle:ReleaseBundleManifest,memberManifestId}。先鉴权，再最多1MiB原始JSON解析，未知字段和尾随内容拒绝。
- 返回200 `ReleaseBundleAck {bundleId,campaignId,version,contentHash,memberSignature,runtimeAck,signature}`。runtimeAck为既有签名READY ACK，外层绑定完整内层规范内容和签名；继承运行端身份、容量、编译ABI和运行密钥配置。没有新业务状态或新凭据来源。
- 验证顺序：可信租户及外层签名/期限 → 成员确属完整组合 → 本站slot和generation持久记录 → 清单完全相等 → 原发布/编译签名、校验和及可执行结构。Journey另须存在与制品相同的已安装定义。所有读取不改变active pointer。
- 错误复用平台Problem；协议错误目录为ReleaseBundleProtocolError。缺失Journey generation沿用既有404，其余准备前提/签名错误409；格式/大小400、缺权限403。无法准备时不以active/latest替代请求成员。
- 每次重试重验并签发新证据，无外部副作用或幂等写回执。旧ACK不能转为新协议；应用上下文重建仍从原持久库核验，而非恢复进程内临时标记。生产多副本和Flink链路仍属于后续整体发布验收。

### S11f3 控制面组合就绪合同（下一片）
- POST `/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/acks` 接受完整ReleaseBundleAck，要求runtime:ack与当前活动范围；GET同级`/readiness`要求release:read与当前活动范围。写入口先鉴权再最多16KiB严格JSON解析。只持久化证据，不产生激活事件。
- 证据必须内外签名都通过现有runtime-ack信任配置，并绑定该活动组合的bundleId/version/contentHash及确切成员签名、manifestId/generation/cell、完整制品集合和ABI。沿用既有release-policy副本/总容量/单副本容量及build digest配置。签发时间在既有5分钟新鲜度及1分钟时钟偏差内；成员或组合过期拒绝。
- V9只增加组合ACK表，主键为tenant/bundle/member/runtime；组合头行锁串行化同组合证据写入，避免空ACK索引锁升级；写事务首个SQL必须为此锁定读，范围/证据的普通读取在其后建立快照，保持原REPEATABLE_READ。每成员最多64个运行身份，整体最多512；固定9位UTC时间保序。原文JSON、签发时间和签名随当前证据保存，audit与更新同事务。成员归属增加组合/成员唯一索引及三列外键，数据库也拒绝把其他组合的清单挂入证据。
- 相同时间与同签名重复成功且不重复审计；相同时间不同内容409；较旧但仍有效的证据不覆盖较新记录。只替换同一身份的更晚证据；到达身份上限拒绝新增，不能无界扩张或悄悄删除审计历史。
- 返回`BundleReadinessView {bundleId,version,contentHash,runtimeReady,checkedAt,expiresAt,members:[{manifestId,runtime,namespace,readyReplicas,readyCapacity,ignoredEvidence,runtimeReady}]}`。每次读取按当前时间与信任重新核验，过期/不匹配/损坏证据不计入，ignoredEvidence可观察。只有每一个成员各自满足副本与容量要求时runtimeReady=true；不能把成员之间的数量或容量合并凑门槛。
- 准备历史的PREPARING不因此被改成ACTIVE。runtimeReady仅代表运行准备条件，正式激活仍在S11g重新核对审批、有效期、当前指针与实时停用；旧单清单ACK不会计入此接口。
- 验收：真实MySQL并发同身份/不同身份、乱序/同时间冲突、审计失败回滚、撤权/跨租户、每成员门槛、过期降级、旧ACK拒绝与零激活副作用。范围为控制面/Mapper/V9、PinnedRuntimeAckVerifier扩展、API与测试；不改运行端、不启用调度。

## S11g1：控制面同slot防绕过
- 作为S11g首个增量，在组合准备事务内把完整slot（tenant/environment/cell/runtime/namespace）持久标记为`bundle_managed`；只从false变true。该标记约束后续发布写入，不切换现有线上配置、不分配活动独占权；多个活动共享slot的运行路由仍须由后续组合上下文解决。
- 任何该slot清单的旧单清单activate/rollback均返回既有409 `RELEASE_BUNDLE_REQUIRED`，包括历史幂等回放和未属于任何组合的新清单。候选读取同样拒绝，避免展示无法执行的回滚。暂存/准备下一组合及紧急KillSwitch保持可用。准备前已经提交的旧激活仍有效，准备提交后不得再产生新的单清单激活。
- V10向既有`mk_release_slot`增加非空默认false字段及中文注释，并由已登记组合成员回填true。正常stage已保证slot存在；准备遇到缺slot视为持久数据不完整，返回现有MEMBER_UNAVAILABLE，不能虚构线上指针。
- 先按manifest ID锁定全部成员，再按environment/cell/runtime/namespace固定顺序更新对应slot；单清单写入在持有manifest行后锁slot并检查当前标记。不能依靠事务早期普通查询的旧快照。所有变更与原组合/成员/审计同事务，失败回滚。只读授权检查用于旧回执前提前拒绝，实际写入另有锁内门禁。
- 扩展迁移不删除数据。启用组合管理前必须让不识别该标记的旧控制面写实例退出；回退先冻结发布写入口。该片未部署，不声称混合版本可安全写入，也不能替代运行端防绕过或真正整体激活。
- 范围：Control应用/Mapper/V10、机器API说明、相关数据库与迁移测试；无新服务/中间件。依赖S11e/f已有合同。验收见PROGRESS的S11g1；接续S11g2再定义可信执行上下文、请求传播和运行端精确版本选择。

## S11g后续边界与S11g2激活授权合同
- 已核对生产Journey通过事件入口和Flink单写者执行，DIRECT HTTP在OIDC下被禁止；因此后续必须同时覆盖Decision、事件路由、Journey持久读和Flink状态，不能只给HTTP请求加字段。同步Decision继续不回源控制面。
- 版本绑定先沿用既有orderId/enrollmentId所代表的一次业务过程，持久键包含tenant/campaign/participationId并绑定subject；是否按会员在活动内的首次参与永久固定已作为可选业务口径询问。未回答前不实现依赖该口径的C端绑定入口，也不猜测真实会员映射。该问题不影响下面的整体激活授权合同。
- Control仍拥有整体发布意图与单调激活序号；签名授权、运行面应用、参与绑定是不同结果。控制台只有取得运行面实际应用证据才能显示整体生效，通信超时须查询原命令，不能当作失败重新创建。
- S11g2只定义并验证独立签名的`ReleaseBundleActivationDirective {directiveId,bundle,activationSequence,action,organizationId,shopId,authorizedAt,authorizedBy,signatureKeyId,signature}`。bundle是原完整ReleaseBundleManifest；action显式稳定ACTIVATE/ROLLBACK。序号在tenant/campaign/environment/cell内递增，回滚也递增但可指向较早组合版本；“曾生效”由后续控制事务检查，不能只凭action字符串认定。
- organizationId来自Control活动归属且非空，shopId为空表示组织级范围；两者随完整bundle、序号、动作和作者/时间进入独立用途`marketing.activity-release-activation/v1`签名。所有成员activationAt<=authorizedAt<最早成员expiresAt，authorizedAt>=bundle.createdAt。运行端同时核验外层授权、内层组合及各原始成员签名，支持信任集合中不同新旧密钥，但外层重新签名不能复活已撤销的原成员签名钥匙，不信任客户端自行声明的scope。
- 只验证授权的租户、信任、未来1分钟偏差与有效期，不能凭密码学验证宣称已经应用。运行持久层还须用单调CAS处理重复/乱序/同序号冲突，验证真实已安装成员、版本绑定与实时停用。这些在后续片完成；旧f2 ACK没有这些能力，不能用它直接开放正式组合激活。
- g2范围：marketing-contracts数据类型/签名验证、OpenAPI共享类型与测试；不增加HTTP激活/签发入口、不写指针或Outbox、不部署。验收覆盖内外签名用途混淆、换租户/活动/成员/权限范围/序号/动作/时间、密钥轮换与到期边界。后续先落实执行绑定与运行端防绕过，再接Control命令、回滚和完整应用回执。

S11g2合同模块保持无JSON生产依赖，动作在wire中使用经Action.fromCode校验的稳定字符串；仅增加仓库已管理Jackson版本的test依赖，验证真实JSON往返和缺字段拒绝，不改变运行镜像依赖。

## S11g3a：Decision持久接收整体授权（执行绑定之前）
- 依赖g2签名合同，增加`POST /api/v1/decisions/runtime/release-bundle-directives`和`GET .../{campaignId}/latest`，均要求runtime:activate。POST先鉴权再严格1MiB解析完整ReleaseBundleActivationDirective，校验本租户/配置environment、cell、namespace对应的唯一decision成员，再重验本站持久清单和制品。只接收控制面可信签名，不提供签发能力。
- 新V4持久表：不可变directive历史与最新“已接收”序号head，按tenant/environment/cell/namespace/campaign隔离。head使用原子upsert争取既有行锁，随后才验证当前时间/本地成员和读取历史；所有写入一个10秒事务。固定序号相同内容重放；同序号异内容、同directiveId换序号409；已记录旧序号重放返回current=false，未记录的过时序号拒绝；新序号可以跳跃接续可信最新授权，绝不倒退head。
- 返回`{state:"RECORDED",directive,latestSequence,current,recordedAt}`。RECORDED只证明持久接收，不是READY或APPLIED；该片不修改旧mk_runtime_slot、在线evaluate或Flink，不把接收回执作为激活能力ACK。GET读取持久原文（包括已过期历史的latest），不延长授权；后续使用时必须重新验签/期限/实时停用。
- 历史与head有完整主键/唯一键、head到历史的复合外键，使用utf8mb4_0900_bin保持签名身份/范围的大小写和尾空格精确性；初始head序号可空且只在同事务内存在，失败整体回滚。保留不可变授权证据，不新增自动清理；生产保留/容量验收仍在S13，不宣称无限容量。
- 验收：真实MySQL/HTTP并发首次与重复、乱序/同序号冲突、不同活动并发、拒绝越权/租户/slot/缺制品/损坏制品/签名/过期、有界解析、应用上下文重建后读取、旧在线指针不变。范围仅Decision、g2共用错误与机器API、V4及测试。无新中间件、线程池、外部调用或共享库迁移。

## S11g3b：Journey持久接收整体授权
- 对Journey增加`POST /api/v1/journey-runtime/release-bundle-directives`与`GET .../{campaignId}/latest`，复用g3a的runtime:activate权限、1MiB边界、RECORDED回执、严格签名及单调接收语义；不跨服务数据库共享表或应用类。
- Journey本库V3新增独立接收历史/head，使用相同完整作用域、二进制NO PAD排序规则与复合外键。接收时复用Journey原持久generation/签名制品/已安装定义核验；缺generation沿用原404，定义缺失或内容不一致拒绝，不伪造ACK。
- 先锁head再查询持久成员，保证事务内一致性；提取包内核验方法供原prepareBundle和新接收服务共用，不放宽HTTP鉴权。Journey旧active pointer、定义、enrollment、Flink与Outbox保持不变。后续Flink只在执行绑定协议齐备后使用这些记录，本片不宣称应用成功。
- 验收：与g3a同级的真实MySQL并发/重放/乱序/失败回滚/重建上下文/输入边界，加已安装Journey定义损坏拒绝和旧运行模块回归。范围Journey服务/V3、机器API与测试；无需新增基础设施或部署。

## S11g4a：参与版本凭证合同
- 沿用已批准的“同一参与保持同一完整组合”。Decision将作为后续准入绑定的唯一写入者，使用自身持久应用指针选择组合；g3的RECORDED head绝不能用于新参与选版。Control拥有发布意图，各运行端仍拥有各自安装/执行状态。本片仅交付跨边界校验合同，不开放签发或绑定HTTP入口。
- 不新增中间件或生产依赖。选用紧凑签名事实+已有完整激活授权，复用Ed25519/CanonicalMapCodec，拒绝每次在线回源Control及以可过期短令牌代替持久绑定：后者会使合法积压/恢复事件失效。可信签发者计划复用Decision现有Offer密钥但使用独立用途`marketing.activity-bundle-participation/v1`；验证方显式分开参与签发信任与Control发布信任，不能合并密钥权限。
- `ReleaseBundleParticipation {bindingId,tenantId,campaignId,environment,cell,participationId,subjectHash,directiveId,activationSequence,activationSignature,bundleId,bundleVersion,contentHash,admittedAt,signatureKeyId,signature}`。tenantId沿用`{value:...}`；参与编号为可信入口给出的1–128字符业务键，主体只放按租户和subjectToken规范长度编码的SHA256，不将哈希当匿名化。参与/主体不修剪或折叠大小写。源激活签名绑定整个活动归属/动作/作者/时间/完整组合，其他冗余索引必须逐项一致，不能只比较bundleId。
- bindingId/活动/组合ID沿用现有最大64字符集合；主体token最长512字符；scope/env/cell沿用原合同，拒绝空值和控制字符。激活签名必须是64字节Ed25519的规范无padding Base64URL（86字符）；自身signature只在本地未签名构造时可空，接收时必须有效。
- 校验函数必须接收可信预期tenant/campaign/environment/cell/participationId/subjectToken，不能从凭证本身生成预期范围。当前Decision信任验证独立签名，精确匹配所有源字段，要求authorizedAt <= admittedAt < bundle.expiresAt且admittedAt不超当前时间60秒。再以admittedAt验证完整Control/成员历史签名和当时期限，保留当前密钥撤销效果。到期后的老参与可通过历史验证，但换参与、换主体、跨租户或重新绑定不能通过。
- 此事实无可续期的访问令牌语义，不能证明仍有资格、未停用或未完成。下游仍必须校验当前身份/范围、停用/风控/同意/配额以及持久执行幂等；Flink TTL/重放后的防重新参与、数据库唯一绑定、原清单保留和终态墓碑属后续必需验收，不能凭签名合同宣布已解决。
- 范围：marketing-contracts类型/签名/校验/错误码、OpenAPI共享schema、测试和说明。无HTTP、迁移、指针、Outbox或生产配置变化。验收覆盖所有字段篡改、用途混用、租户/主体/参与/活动/位置误绑、源授权替换、内层密钥撤销、历史到期恢复及准入时间边界、真实JSON往返/缺字段/非法值。依赖g2，owner=backend；不可并行修改共享合同。
- 后续g4b先实现运行端持久slot防单清单绕过，再接实际APPLIED准入指针与唯一参与绑定、Decision精确选版及事件/Flink传播；只有各入口和旧版本退出门禁齐备后才开放Control整体发布。实际C端业务编号选择和真实会员映射继续依赖上游合同，不阻止通用事实协议。
