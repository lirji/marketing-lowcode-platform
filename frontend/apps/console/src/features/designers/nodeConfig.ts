export const NODE_FIELD_KIND = { INTEGER: 'integer', TIME: 'time', CHOICE: 'choice', BENEFIT: 'benefit' } as const
/** 字段与当前 OfferPolicyGraphCompiler/JourneyRuntime 及副作用消费者契约对应。 */
export type NodeField = {
  key: string; label: string; hint: string; kind?: typeof NODE_FIELD_KIND[keyof typeof NODE_FIELD_KIND]
  options?: string[]; min?: number; max?: number; defaultValue?: string; required?: boolean
}
const text = (key: string, label: string, hint: string): NodeField => ({ key, label, hint })
const integer = (key: string, label: string, hint: string, min = 0, max?: number): NodeField => ({ key, label, hint, kind: NODE_FIELD_KIND.INTEGER, min, max })
const choice = (key: string, label: string, hint: string, options: string[], defaultValue: string): NodeField => ({ key, label, hint, kind: NODE_FIELD_KIND.CHOICE, options, defaultValue })
const offerFields: NodeField[] = [
  { key: 'validFrom', label: '生效时间', hint: '按北京时间选择，发布后才参与生效判断。', kind: NODE_FIELD_KIND.TIME, required: true },
  { key: 'validTo', label: '失效时间', hint: '必须晚于生效时间。', kind: NODE_FIELD_KIND.TIME, required: true },
  { key: 'benefitDefinitionVersion', label: '权益定义版本', hint: '选择已登记权益的固定版本；发布时仍须通过权益审核与 SKU 校验。', kind: NODE_FIELD_KIND.BENEFIT, required: true },
  integer('thresholdMinor', '订单门槛（分）', '0 表示无最低金额；100 表示 1 元。'),
  integer('capMinor', '优惠封顶（分）', '0 表示不额外封顶，优惠仍不会超过可优惠金额。'),
  integer('maxApplications', '最多应用次数', '单次定价中可应用的次数，默认 1。', 1),
  choice('stage', '优惠阶段', '与其他优惠按照阶段依次计算。', ['BASE', 'ITEM', 'SHOP', 'CROSS_SHOP', 'PLATFORM', 'COUPON', 'SHIPPING', 'PAYMENT_DISPLAY'], 'PLATFORM'),
  text('stackGroup', '叠加组', '相同组使用同一种命中策略；默认 default。'),
  choice('hitPolicy', '组内命中策略', 'BEST 择优、FIRST 按优先级首个、SUM 累加、MAX_N 限制数量。', ['BEST', 'FIRST', 'SUM', 'MAX_N'], 'BEST'),
  integer('maxGroupHits', '组内最多命中数', 'MAX_N 策略的数量上限，默认 1。', 1),
  integer('priority', '优先级', '用于 FIRST 策略；默认 0。', -2147483648, 2147483647),
  text('offerId', '优惠业务 ID', '留空使用节点 ID；同一图中必须唯一。'),
  text('incompatibleOfferIds', '互斥优惠 ID', '逗号分隔；只允许引用本图其他优惠。'),
  text('fundingRules', '资金承担规则', '每项为类型|主体ID|万分比，用分号分隔；默认 PLATFORM|platform|10000。'),
  text('reasonCode', '命中原因码', '稳定业务原因码；默认 OFFER_MATCHED。'),
  ...(['channels', 'skuIds', 'categoryIds', 'shopIds', 'eligibleLineIds'] as const).map((key, i) => text(key, ['渠道范围', '商品 SKU 范围', '品类范围', '店铺范围', '购物车行范围'][i], '逗号分隔，留空不限制；需要真实上下文，金额试算不支持该范围。')),
  text('requiredAudienceSnapshotId', '人群快照 ID', '绑定已登记的不可变快照；金额试算不核验人群资格。'),
]
const sendFields: NodeField[] = [
  choice('channel', '触达渠道', '运行时仍校验当前同意、模板和频控。', ['SMS', 'EMAIL', 'PUSH'], ''),
  text('templateId', '模板 ID', '使用本租户已批准且与渠道一致的模板。'),
  integer('templateVersion', '模板版本', '固定模板版本，默认 1。', 1),
  text('campaignId', '频控活动 ID', '留空使用旅程 ID。'),
  text('recipientToken', '收件人令牌', '使用脱敏令牌；留空使用参与者令牌，禁止填写真实手机号或邮箱。'),
  text('timezone', '收件人时区', '例如 Asia/Shanghai；默认 UTC，用于静默时段判断。'),
  choice('personalized', '个性化触达', '启用后还需当前个性化授权，未成年人禁止。', ['false', 'true'], 'false'),
]
export const NODE_LABELS: Record<string, string> = {
  'offer.start': '开始', 'offer.fixed': '定额优惠', 'offer.percentage': '比例优惠', 'offer.end': '结束',
  'journey.trigger': '开始', 'journey.condition': '条件分支', 'journey.wait-event': '等待事件',
  'journey.wait-timer': '等待时间', 'journey.send': '发送消息', 'journey.grant': '资源发放',
  'journey.webhook': 'Webhook', 'journey.end': '结束',
}
export function nodeFields(type: string): NodeField[] {
  switch (type) {
    case 'offer.fixed': return [integer('amountMinor', '减免金额（分）', '整数分，例如 800 表示减 8 元。'), ...offerFields]
    case 'offer.percentage': return [integer('discountBasisPoints', '优惠比例（万分比）', '1000 表示优惠 10%（九折），范围 0–10000。', 0, 10000), ...offerFields]
    case 'journey.condition': return [text('field', '旅程变量名', '对入组/事件传入的变量进行精确匹配。'), text('equals', '等于', '与变量字符串完全相同走 true，否则走 false。')]
    case 'journey.wait-event': return [text('eventType', '等待事件类型', '例如 ORDER_PAID；* 匹配任意事件。')]
    case 'journey.wait-timer': return [integer('delaySeconds', '等待时长（秒）', '以事件发生时间计算到期时间。')]
    case 'journey.send': return sendFields
    case 'journey.grant': return [text('resourceKey', '资源键', '已登记的库存或奖品资源，不支持预算账户。'), integer('quantity', '发放数量', '正整数，默认 1。', 1), text('benefitId', '权益 ID', '留空使用资源键。'), integer('expectedFencingEpoch', '预期资源代次', '可选；留空使用当前账户代次。', 1)]
    case 'journey.webhook': return [text('endpointToken', '接收端令牌', '真实地址由服务端配置，不接受任意 URL。'), text('action', '动作', '留空使用节点 ID。')]
    default: return []
  }
}

/** 只展示当前编辑器与运行时共同支持的节点；保留已加载未知节点但不冒充可配置。 */
export const supportedNode = (type: string) => Object.hasOwn(NODE_LABELS, type)
