import type { Campaign } from '../../shared/model/types'

export const campaignStatusMeta: Record<Campaign['status'], { label: string; tone: 'good'|'warn'|'danger'|'neutral'|'info' }> = {
  DRAFT: { label: '草稿', tone: 'neutral' }, IN_REVIEW: { label: '审核中', tone: 'warn' },
  APPROVED: { label: '已批准', tone: 'info' }, ACTIVE: { label: '运行中', tone: 'good' },
  PAUSED: { label: '已暂停', tone: 'danger' }, ENDED: { label: '已结束', tone: 'neutral' },
}
