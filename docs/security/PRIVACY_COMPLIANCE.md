# 隐私、自动化决策与促销合规设计

本文是工程控制清单，不替代中国或运营地区法律意见。正式上线需由法务、隐私、消费者权益和财务负责人按实际数据/业务确认。

## 数据字段治理

Field Registry 对每个字段记录 data owner、source/provenance、purpose、lawful basis/consent、sensitivity、retention、allowed dialect/action、freshness 和 masking。未登记字段不能进入 Audience/Decision/Journey；高敏字段默认不能导出、抽样或进入日志。

Subject identifier 在事件/分析中 tokenization；身份映射保留在更高保护域。删除请求通过 lineage 找到 OLTP、Audience snapshot、ClickHouse、S3、DLQ 和备份策略；依法必须保留的账本进入受限 legal hold，不继续用于营销。

## 自动化决策

个性化 Campaign 必须：

- 标识个性化目的、使用字段、主要因素和 reason code；
- 提供不针对个人特征的可访问路径；
- 提供便捷退出并在实际动作前重新检查；
- 检查不合理差别待遇、代理变量、群体结果和投诉信号；
- 对影响权益较大的拒绝/差价提供人工复核路径；
- 固定 TermsSnapshot、Audience/Artifact/Experiment version，支持事后解释。

Experiment assignment 不代表用户已看到内容；只有真实 exposure 才进入衡量。Holdout 不执行营销副作用，避免“对照组也领券”。

## 促销公示

发布前消费者条款至少包含时间、地域/店铺/商品、人群条件的可理解表达、门槛、优惠计算、数量/预算限制、叠加/互斥、退款返还、价格基准、个性化标识、出资责任和投诉渠道。TermsSnapshot 不可变并关联 Decision、展示和 PromotionApplication。

原价/划线价/最低价基准不能由运营自由文本冒充，必须来自受治理 price reference adapter，携带 asOf、范围和计算政策。商家/品牌出资需要明确 opt-in 和 FundingShare，平台不能用审批绕过出资授权。

## Consent、Suppression 与触达

渠道、purpose 和地域分别记录 consent；退订、投诉、黑名单、未成年人、quiet hours、频次和 provider policy 在发送前最后一跳重新检查。模板变量 schema 防止把未许可 PII 注入消息。退订优先级高于队列中的旧 campaign；suppression 更新需通过高优先通道传播。

## 保留与访问

生产前为每类数据批准 TTL：raw events、decision trace、contact content、Audience membership、DLQ、analytics、audit、ledger 和 backup 分别设置。审计/资金法定留存不意味着可供营销查询。客服/分析访问最小化、目的绑定、行列脱敏和审计；批量导出需要审批、水印、加密和自动到期。

## 发布合规门禁

阻止发布：缺 Terms、个性化无通用路径/退出、使用无目的许可字段、Audience provenance/freshness 不明、商家未 opt-in、Funding 不守恒、触达无 consent policy、未成年人策略缺失、价格基准无来源或 fairness 检查失败。高风险活动要求人工合规批准，系统检查不能自动替代判断。
