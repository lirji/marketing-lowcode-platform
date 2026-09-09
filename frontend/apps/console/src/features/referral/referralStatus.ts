const QUALIFICATION_STATES: Record<string, string> = {
  ELIGIBLE: '规则候选已产生（仿真，不是到账）',
  INELIGIBLE: '未达标',
  PENDING: '观察中 / 等待核验',
  REVIEW: '审核中',
}

const QUALIFICATION_REASONS: Record<string, string> = {
  QUALIFIED: '规则判定可产生候选',
  BIND_OUTSIDE_WINDOW: '绑定不在活动窗口内',
  EVIDENCE_UNAVAILABLE: '证据不足，等待核验',
  NOT_NEW_CUSTOMER: '非新客',
  NOT_FIRST_ORDER: '不是权威首单',
  FACT_OUTSIDE_WINDOW: '资格事实不在窗口内',
  FUTURE_EVIDENCE: '证据时间尚未到达',
  CURRENCY_MISMATCH: '币种与规则不一致',
  INVALID_AMOUNTS: '金额不合法',
  NET_BELOW_THRESHOLD: '退款后净额不足',
  OBSERVATION_PENDING: '观察期未满',
  OBSERVATION_OUTSIDE_SETTLEMENT: '观察期超出结算截止',
  LATE_REVIEW: '晚到证据需复核',
}

const FORBIDDEN_SUCCESS = /已到账|已发放成功|领取成功|奖励到账/

export function qualificationStateLabel(state: string | undefined): string {
  if (!state) return '状态待确认'
  return QUALIFICATION_STATES[state] ?? '状态待确认'
}

export function qualificationReasonLabel(reason: string | undefined): string {
  if (!reason) return '原因待确认'
  return QUALIFICATION_REASONS[reason] ?? '状态待确认'
}

export function unknownEnumLabel(value: string | undefined, known: Record<string, string>): { label: string; known: boolean } {
  if (!value) return { label: '状态待确认', known: false }
  const label = known[value]
  return label ? { label, known: true } : { label: '状态待确认', known: false }
}

export function assertNotPaidCopy(text: string): boolean {
  return !FORBIDDEN_SUCCESS.test(text)
}

export { QUALIFICATION_STATES, QUALIFICATION_REASONS }
