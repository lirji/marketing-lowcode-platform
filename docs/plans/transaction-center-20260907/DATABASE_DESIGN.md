# 交易中心数据库详细设计

状态：设计字典，未执行 DDL。基线 MySQL 8.4；完整 Flyway SQL 是实施交付物，不能用本文字典替代迁移。所有新表、所有字段必须有非空中文 COMMENT，包括技术表。

部署约束：优先复用 Docker dev_infra 已有 `mysql84`（镜像 `mysql:8.4`），仅拟新增逻辑库 `transaction_center` 和最小权限应用／迁移账号，不新增数据库容器、不复用其他项目业务表。容器连接 `infra-mysql84:3306`，宿主机连接 `127.0.0.1:43306`；凭据由环境或密钥机制注入。所有模块首期共用该逻辑库和同一事务管理器，保留本地事务一致性。具体组件及生产隔离边界见[总纲复用清单](FINAL_PLAN.md)。本文未创建逻辑库、账号或数据表。

## 1. 通用规则

表前缀 `tc_`。下文每张表均继承公共字段；每个 `字段：说明` 的说明即该字段注释的最低语义要求，实施需在 COMMENT 中保留。表标题的中文名即表注释。历史事实默认不可物理删除；公共 `updated_at` 不授权修改资金事实。

| 公共字段 | 类型／约束 | 必须写入的字段注释 |
|---|---|---|
| id | BIGINT UNSIGNED PK | 内部主键，由统一 ID 生成器生成，不作为授权凭据 |
| tenant_id | BIGINT UNSIGNED NOT NULL | 所属租户标识，所有业务读写必须限定租户 |
| created_at | DATETIME(3) NOT NULL | 创建时间，统一保存 UTC 毫秒时间 |
| updated_at | DATETIME(3) NOT NULL | 最近更新时间，统一保存 UTC 毫秒时间 |
| version | BIGINT UNSIGNED NOT NULL DEFAULT 0 | 乐观并发版本号，每次状态更新递增 |

缩写：S=VARCHAR(64)，L=VARCHAR(255)，T=DATETIME(3)，N=BIGINT UNSIGNED，J=JSON，E=VARCHAR(32)。除显式标记“可空”外均 NOT NULL；字符串无隐式空串默认值。状态枚举由合同和 CHECK 双重约束。所有金额 N、币种 CHAR(3)、数量 N；差量字段使用有符号 BIGINT。用户输入 ID 与摘要用二进制排序规则或 VARBINARY，避免大小写归一导致碰撞。ID 在 JSON 中输出字符串。

U 表示租户前缀唯一索引，I 表示租户前缀普通索引。所有关联显式带 tenant_id；业务父表提供 `(tenant_id,id)` 唯一键，首期适用处建立复合 FK（不级联删除）。跨系统仅保存外部引用不设 FK。上线前用真实 EXPLAIN 和索引大小报告确认，不给所有 JSON 字段建索引。

## 2. 会员与商品

| 表／中文表注释 | 专有字段及其中文注释 | 关键索引与规则 |
|---|---|---|
| `tc_member` 业务会员主表 | member_no S：对外会员编号；status E：会员业务状态；registered_at T：业务注册成功时间；display_name L可空：会员展示名称；phone_cipher VARBINARY(512)可空：手机号密文；phone_digest VARBINARY(64)可空：手机号带密钥检索摘要；key_version S可空：个人信息加密密钥版本 | U(member_no)；I(phone_digest) 非唯一，号码更换／共享不能误合并会员 |
| `tc_member_identity` 登录主体与会员映射表 | member_id N：会员主键；issuer L：身份签发方；subject L：登录主体原始标识；identity_digest BINARY(32)：租户签发方主体规范编码后的 SHA-256；status E：绑定状态 | U(identity_digest)；冲突须比对 issuer/subject 原值，不静默串绑；I(member_id) |
| `tc_member_consent` 会员同意记录表 | member_id N：会员主键；purpose S：同意用途；document_version S：条款版本；action E：同意或撤回；occurred_at T：动作发生时间；source_request_id S：来源请求号 | U(source_request_id)；I(member_id,purpose,occurred_at)；追加记录 |
| `tc_member_trade_fact` 会员消费事实表 | member_id N：会员主键；first_paid_order_id N可空：首次确认支付订单；first_confirmed_at T可空：首笔足额支付本地确认时间；ever_paid TINYINT：是否曾有有效足额支付；paid_order_count N：成功支付订单累计数；fact_version N：消费事实版本 | U(member_id)；首付三字段同事务写，退款不清除 ever_paid；注册时建立空事实行 |
| `tc_product` 商品主档表 | product_no S：商品编号；name L：商品名称；status E：商品上下架状态；published_revision N可空：当前发布版本；description J可空：结构化商品描述 | U(product_no)；I(status,id) |
| `tc_sku` 商品 SKU 主档表 | product_id N：商品主键；sku_no S：SKU 编号；specification J：规格属性；stock_type E：实物或无库存服务类型；status E：SKU 可售状态；current_revision N：当前版本号 | U(sku_no)；I(product_id,status,id) |
| `tc_sku_revision` SKU 不可变发布快照表 | sku_id N：SKU 主键；revision N：发布版本；title L：交易展示名称；specification J：规格快照；unit_price N：最小单位售价；currency CHAR(3)：售价币种；published_at T：发布时间 | U(sku_id,revision)；已引用快照不可覆盖，编辑新建版本 |
| `tc_quote` 服务端报价表 | quote_no S：报价编号；member_id N：所属会员；items J：SKU 版本数量单价组成的报价快照；payable_amount N：报价应付金额；currency CHAR(3)：报价币种；expires_at T：报价失效时间；pricing_policy S：定价策略版本；snapshot_hash BINARY(32)：规范化快照摘要 | U(quote_no)；I(member_id,created_at)；金额与数量服务端校验；有效报价锁价，下架仍禁止新下单 |

## 3. 库存与订单

| 表／中文表注释 | 专有字段及其中文注释 | 关键索引与规则 |
|---|---|---|
| `tc_warehouse` 仓库主档表 | warehouse_no S：仓库编号；name L：仓库名称；status E：仓库状态 | U(warehouse_no) |
| `tc_inventory_balance` SKU 仓库库存余额表 | warehouse_id N：仓库主键；sku_id N：SKU 主键；stock_total N：累计库存总量扣除盘亏等调整后的数量；available N：可预占库存；reserved N：订单预占库存；sold N：已确认销售且未退回可售库存 | U(warehouse_id,sku_id)；CHECK(stock_total=available+reserved+sold) |
| `tc_inventory_reservation` 订单库存预占明细表 | order_id N：订单主键；order_item_id N：订单明细主键；warehouse_id N：仓库主键；sku_id N：SKU 主键；quantity N：预占数量；status E：预占确认或释放状态；expires_at T：触发关单检查时间而非自动释放承诺；confirmed_at T可空：确认扣减时间；released_at T可空：释放时间 | U(order_item_id,warehouse_id)；I(status,expires_at,id)；状态条件更新，不允许确认后释放 |
| `tc_inventory_ledger` 库存变动审计流水表 | operation_no S：库存操作唯一编号；reservation_id N可空：关联预占；warehouse_id N：仓库主键；sku_id N：SKU 主键；operation_type E：预占确认释放调整或退货；available_delta BIGINT：可售库存变动量；reserved_delta BIGINT：预占库存变动量；sold_delta BIGINT：已售库存变动量；total_delta BIGINT：总库存变动量；source_ref S：来源单据编号；reason L：变动原因 | U(operation_no)；I(warehouse_id,sku_id,id)；余额与流水同事务写入 |
| `tc_order` 交易订单主表 | order_no S：订单编号；member_id N：购买会员；quote_id N：报价主键；merchant_no S：收款商户编号；status E：订单履约状态；financial_status E：未付已付部分退或全退；gross_amount N：原价总额；discount_amount N：优惠总额；freight_amount N：运费；tax_amount N：额外税额；payable_amount N：冻结应付金额；currency CHAR(3)：订单币种；expires_at T：支付截止检查时间；paid_at T可空：本地确认支付时间；completed_at T可空：商业完成时间；cancelled_at T可空：取消时间；cancel_reason L可空：取消原因；referral_ref S可空：营销侧签发并核验的归因引用 | U(order_no)；U(quote_id) 首期报价只建一单；I(member_id,created_at,id)；I(status,expires_at,id)；应付=原价-优惠+运费+税 |
| `tc_order_item` 订单明细冻结快照表 | order_id N：订单主键；sku_id N：SKU 主键；sku_revision N：SKU 版本；title L：商品名称快照；specification J：规格快照；quantity N：购买数量；unit_price N：单件价格；gross_amount N：明细原价；discount_amount N：明细优惠；payable_amount N：明细应付 | I(order_id,id)；所有明细和订单金额一致，首期优惠为零 |
| `tc_order_fulfillment` 订单履约事实表 | order_id N：订单主键；fulfillment_no S：履约单号；status E：履约状态；delivery_ref L可空：物流或服务交付引用；delivered_at T可空：交付时间；completed_at T可空：确认完成时间；source_request_id S：来源幂等请求号 | U(fulfillment_no)；U(source_request_id)；U(order_id) 首期一单一履约 |
| `tc_order_address` 订单收货地址快照表 | order_id N：订单主键；recipient_cipher VARBINARY(512)：收件人密文；phone_cipher VARBINARY(512)：联系电话密文；address_cipher BLOB：完整地址密文；key_version S：密钥版本 | U(order_id)；仅实物订单建立；访问脱敏与审计 |
| `tc_return_receipt` 退货验收与入库记录表 | return_no S：退货单号；order_item_id N：原订单明细；warehouse_id N：接收仓库；received_quantity N：本次收货数量；restock_quantity N：本次可售入库数量；status E：待验收通过或不通过；inspection_ref L：验收证据引用；restock_operation_no S可空：库存唯一操作号 | U(return_no)；U(restock_operation_no)；同订单明细累计退货不得超过已履约数量，事务内锁定明细校验 |

## 4. 支付、退款及基础设施

| 表／中文表注释 | 专有字段及其中文注释 | 关键索引与规则 |
|---|---|---|
| `tc_merchant_channel` 商户支付渠道配置表 | merchant_no S：业务商户编号；channel_code S：渠道类型；channel_merchant_no L：渠道商户编号；credential_ref L：密钥托管引用而非密钥；config_version N：配置版本；status E：渠道启停状态；currency CHAR(3)：允许币种 | U(merchant_no,channel_code,currency)；渠道商户映射在回调入口具有全局明确路由，不允许跨租户配置同一收款主体而无法区分 |
| `tc_payment_intent` 订单支付意图表 | payment_no S：支付意图编号；order_id N：订单主键；status E：支付意图状态；expected_amount N：应收金额；currency CHAR(3)：币种；active_attempt_id N可空：活跃或未知尝试；succeeded_receipt_id N可空：用于正常订单履约的收款事实；close_fence_at T可空：本地停止新支付尝试时间 | U(payment_no)；U(order_id)；串行锁定意图管理尝试，活跃指针唯一 |
| `tc_payment_attempt` 渠道支付尝试表 | intent_id N：支付意图主键；channel_code S：渠道类型；channel_merchant_no L：渠道商户号；merchant_request_no S：稳定渠道请求号；status E：创建处理中未知成功失败或关闭；provider_payment_no L可空：渠道支付单号；config_version N：渠道配置版本；submitted_at T可空：外部调用开始时间；provider_paid_at T可空：渠道支付时间；next_query_at T可空：下次查单时间；error_code S可空：标准化失败码 | U(channel_code,channel_merchant_no,merchant_request_no)；I(status,next_query_at,id)；外部调用请求号终身不可复用于新支付 |
| `tc_payment_receipt` 已确认真实收款事实表 | attempt_id N：支付尝试；order_id N：关联订单；channel_code S：渠道类型；channel_merchant_no L：渠道商户号；provider_transaction_no L：真实扣款交易号；amount N：渠道实收金额；currency CHAR(3)：实收币种；provider_paid_at T：渠道扣款时间；confirmed_at T：本地确认时间；disposition E：正常多付金额异常或待匹配；refunded_amount N：累计确认退款；refund_reserved_amount N：在途退款预占 | U(channel_code,channel_merchant_no,provider_transaction_no)；I(order_id,id)；CHECK(refunded_amount+refund_reserved_amount<=amount)；异常多付仍保留独立事实 |
| `tc_payment_callback` 渠道回调接收记录表 | channel_code S：渠道类型；channel_merchant_no L：渠道商户号；provider_event_id L：渠道事件编号或合同约定的确定性事件键；payload_hash BINARY(32)：原始报文摘要；payload_cipher MEDIUMBLOB：必要回调密文；key_version S：回调加密密钥版本；signature_version S：验签证书版本；status E：待处理成功或异常；received_at T：首次接收时间；processed_at T可空：业务处理完成时间；last_error L可空：最近脱敏错误 | U(channel_code,channel_merchant_no,provider_event_id)；I(status,received_at,id)；相同事件键不同摘要报警，不能覆盖原报文 |
| `tc_refund` 退款申请与执行表 | refund_no S：退款编号；receipt_id N：退款来源真实收款；order_id N：关联订单；amount N：本次退款金额；currency CHAR(3)：币种；reason L：退款原因；status E：待审批处理中未知成功或失败；source_request_id S：来源幂等请求号；merchant_refund_no S：稳定渠道退款号；provider_refund_no L可空：渠道退款编号；approval_ref S可空：审批关联号；succeeded_at T可空：确认成功时间；next_query_at T可空：下次查询时间 | U(refund_no)；U(source_request_id)；U(merchant_refund_no)；I(status,next_query_at,id)；创建申请与预占 receipt 额度同事务 |
| `tc_refund_item` 退款金额归属明细表 | refund_id N：退款主键；order_item_id N可空：商品明细主键；component_type E：商品运费税或多付补偿；quantity N：涉及商品数量非商品为零；amount N：本项退款金额 | I(refund_id,id)；各项合计等于退款额；明细累计不得超过冻结可退额 |
| `tc_money_ledger` 交易运营资金事实流水表 | ledger_no S：流水编号；receipt_id N：原始收款事实；refund_id N可空：关联退款；entry_type E：收款或退款；amount N：正数资金量；currency CHAR(3)：币种；business_key S：业务去重键；occurred_at T：资金发生时间；evidence_ref L：渠道证据引用 | U(ledger_no)；U(business_key)；不可变，不等于完整财务总账或渠道清算余额 |
| `tc_idempotency` API 幂等请求记录表 | actor_key S：调用主体；operation S：业务操作；request_key S：幂等键；request_hash BINARY(32)：规范化请求摘要；status E：处理中或已完成；resource_no S可空：业务资源编号；response_snapshot J可空：不含密钥的稳定响应；expires_at T：响应快照保留期限 | U(actor_key,operation,request_key)；I(expires_at,id)；关键业务唯一键不得随该表清理而失效 |
| `tc_outbox` 本地事务待发布事件表 | event_id S：全局事件编号；aggregate_type S：聚合类型；aggregate_id S：聚合编号；aggregate_version N：聚合版本；event_type S：事件类型；payload J：脱敏事件载荷；status E：待发或已发；attempts N：尝试次数；next_attempt_at T：重试时间；published_at T可空：消息平台确认时间 | U(event_id)；I(status,next_attempt_at,id)；业务事实与事件同事务 |
| `tc_inbox` 消费者事件去重表 | consumer_name S：消费者名称；event_id S：源事件编号；payload_hash BINARY(32)：载荷摘要；processed_at T：业务处理提交时间 | U(consumer_name,event_id)；业务效果与去重记录同事务，不先标成功再执行业务 |
| `tc_job_lease` 可恢复后台任务表 | job_type S：任务类型；business_key S：业务对象；status E：等待执行完成或人工处理；owner S可空：当前工作者；lease_until T可空：租约到期时间；fencing_token N：每次接管递增令牌；attempts N：尝试次数；next_run_at T：下次执行时间；last_error L可空：最近脱敏错误 | U(job_type,business_key)；I(status,next_run_at,id)；任务完成更新必须携带当前令牌 |
| `tc_operation_audit` 高风险操作审计表 | actor_ref S：操作主体；action S：业务动作；resource_type S：资源类型；resource_no S：资源编号；reason L：操作原因；before_hash S可空：变更前摘要；after_hash S可空：变更后摘要；trace_id S：链路编号 | I(resource_type,resource_no,id)；I(actor_ref,created_at,id)；限制更新删除权限，敏感原文不入审计 |
| `tc_reconciliation_case` 渠道交易差异处置表 | case_no S：差异编号；channel_code S：渠道类型；statement_date DATE：账单日期；external_line_key S：账单行稳定键；business_ref S可空：本地交易引用；difference_type E：长短款金额或状态差异；status E：待确认处理中或关闭；resolution_ref S可空：处置命令或外部工单号；evidence_ref L：账单证据引用 | U(case_no)；U(channel_code,statement_date,external_line_key,difference_type)；对账平台负责比对，此表保留交易侧执行及审计关联 |

## 5. 带完整注释的 DDL 样例

```sql
CREATE TABLE tc_inventory_balance (
  id BIGINT UNSIGNED NOT NULL COMMENT '库存余额内部主键',
  tenant_id BIGINT UNSIGNED NOT NULL COMMENT '所属租户标识，所有库存操作必须限定租户',
  warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '所属仓库主键',
  sku_id BIGINT UNSIGNED NOT NULL COMMENT '所属商品SKU主键',
  stock_total BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '经到货退货及盘点调整后的库存总量',
  available BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前可被订单预占的库存数量',
  reserved BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已被未决订单预占的库存数量',
  sold BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已确认销售且尚未退回可售库存的数量',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '并发版本号，每次变动递增',
  created_at DATETIME(3) NOT NULL COMMENT '记录创建UTC时间，毫秒精度',
  updated_at DATETIME(3) NOT NULL COMMENT '记录最近更新UTC时间，毫秒精度',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_tenant_id (tenant_id, id),
  UNIQUE KEY uk_inventory_tenant_warehouse_sku (tenant_id, warehouse_id, sku_id),
  CONSTRAINT ck_inventory_conservation CHECK (stock_total = available + reserved + sold)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='交易中心SKU仓库库存余额表，和库存流水同事务维护';
```

该独立样例未包含仓库／SKU 的 FK，因为父表迁移未在本文执行；完整迁移按父子顺序创建并补复合外键。预占采用 `UPDATE ... SET available=available-:quantity,reserved=reserved+:quantity,version=version+1 WHERE tenant_id=:tenantId AND warehouse_id=:warehouseId AND sku_id=:skuId AND available>=:quantity`，必须检查影响行数，并在同事务完成订单、预占及流水。禁止先查余额再无条件覆盖。

## 6. 注释、迁移、归档门禁

- Flyway 从空库创建和上一版本升级两条路径均必须通过；上线前审计 information_schema.TABLES.TABLE_COMMENT 和 COLUMNS.COLUMN_COMMENT，覆盖本项目全部 `tc_` 表，任何空注释失败。字典名称与 SQL 注释对照检查，不能只满足“有任意字符”。
- ALTER 新增字段、拆表、Inbox/Outbox、测试表同样必须注释；发布 SQL 禁止把本文省略号或类型缩写当实际 DDL。
- 类、公共方法采用中文 Javadoc；金额舍入、终态竞态、租户权限、幂等分支说明原因和不变量。Checkstyle 自定义门禁检查覆盖，人工评审判断注释准确性；不要求无意义地逐行翻译代码。
- 幂等响应快照建议至少 7 天，具体按重试周期定版；支付请求号／交易号和业务去重事实按资金记录保留策略持久存在。Inbox 清理须晚于可重放消息范围，或保留业务唯一键兜底。
- 回调原文仅保留必要密文，建议在线 30 天后受控归档，最终时长待合规确认；资金账本不可因技术 TTL 删除。Outbox 已发送归档与未发积压分别管理。
- 首期不启用 MySQL 原生分区，以免破坏唯一键／外键设计。大表先索引和冷热归档，后续分区或拆库须重新验证唯一性、FK 替代与历史查询。
- 开发种子数据写数据库、前端走 API；种子脚本幂等且明确环境保护。生产禁止默认账号／测试支付回调密钥／模拟渠道配置。
