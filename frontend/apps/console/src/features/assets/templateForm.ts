import type { TemplateView } from '../../shared/api/schemas'

export const TEMPLATE_STATE = { APPROVED: 'APPROVED' } as const
const EMAIL_CHANNEL_CODE = 'EMAIL'
const EMAIL_UNSUBSCRIBE_TEXT = 'unsubscribe'

export type TemplateForm = { templateId: string; version: string; channel: string; content: string; variables: string }
export function templateForm(source?: TemplateView): TemplateForm {
  return { templateId: source?.templateId ?? '', version: String(source ? source.version + 1 : 1), channel: source?.channel ?? '', content: source?.content ?? '', variables: source?.requiredVariables.join('\n') ?? '' }
}
export function templatePayload(form: TemplateForm) {
  return { templateId: form.templateId, version: Number(form.version), channel: form.channel, content: form.content, requiredVariables: form.variables.split(/\r?\n/).filter((value) => value !== '') }
}
export function templateValidation(form: TemplateForm, previousVersion = 0): string | undefined {
  const payload = templatePayload(form)
  if (!form.templateId || form.templateId.length > 128 || /\s/.test(form.templateId) || [...form.templateId].some((value) => value.charCodeAt(0) < 32 || (value.charCodeAt(0) >= 127 && value.charCodeAt(0) <= 159))) return '填写1–128字符的模板 ID，不能含空白或控制字符。'
  if (!Number.isSafeInteger(payload.version) || payload.version < 1 || payload.version <= previousVersion) return '目标版本须为大于当前版本的安全正整数。'
  if (!form.channel || form.channel.length > 32 || /\s/.test(form.channel) || [...form.channel].some((value) => value.charCodeAt(0) < 32 || (value.charCodeAt(0) >= 127 && value.charCodeAt(0) <= 159))) return '填写1–32字符的渠道标识，不能含空白或控制字符。'
  if (!form.content.trim() || form.content.length > 8000) return '模板正文须为1–8000字符，不能全部为空白。'
  if (form.channel === EMAIL_CHANNEL_CODE && !form.content.includes(EMAIL_UNSUBSCRIBE_TEXT)) return 'EMAIL模板必须包含 unsubscribe 退订说明。'
  if (payload.requiredVariables.length > 32 || payload.requiredVariables.some((value) => !/^[A-Za-z_][A-Za-z0-9_.-]{0,59}$/.test(value)) || new Set(payload.requiredVariables).size !== payload.requiredVariables.length) return '必需变量不能重复，须符合名称规则且最多32个；请检查每行的空白。'
  return undefined
}
