import type { LucideIcon } from 'lucide-react'
import { Activity, BarChart3, Boxes, CircleGauge, GitPullRequestArrow, Megaphone, PenTool, Rocket, ShieldCheck } from 'lucide-react'

export type NavigationItem = { label: string; path: string; icon: LucideIcon; permissions: string[] }
export type NavigationGroup = { label: string; items: NavigationItem[] }

export const navigation: NavigationGroup[] = [
  { label: '运营', items: [
    { label: '总览', path: '/', icon: CircleGauge, permissions: ['campaign:read', 'measurement:read'] },
    { label: '计划与活动', path: '/campaigns', icon: Megaphone, permissions: ['campaign:read'] },
  ] },
  { label: '低代码设计', items: [
    { label: 'Offer 设计器', path: '/designers/offer', icon: PenTool, permissions: ['definition:read', 'definition:write'] },
    { label: 'Audience Builder', path: '/designers/audience', icon: GitPullRequestArrow, permissions: ['audience:read', 'audience:preview', 'audience:write'] },
    { label: 'Journey 设计器', path: '/designers/journey', icon: Activity, permissions: ['definition:read', 'journey:read'] },
  ] },
  { label: '资产与规则', items: [
    { label: 'DMN 决策表', path: '/designers/dmn', icon: GitPullRequestArrow, permissions: ['definition:read'] },
    { label: '权益与资金', path: '/designers/benefit', icon: Boxes, permissions: ['benefit:read', 'benefit:write'] },
    { label: '营销资产', path: '/assets', icon: Boxes, permissions: ['audience:read', 'benefit:read', 'template:read', 'audience-field:read', 'definition:read'] },
  ] },
  { label: '治理', items: [
    { label: '审核与风险', path: '/governance', icon: ShieldCheck, permissions: ['approval:business', 'approval:finance', 'approval:compliance', 'approval:merchant', 'definition:read'] },
    { label: '发布中心', path: '/releases', icon: Rocket, permissions: ['release:read'] },
  ] },
  { label: '洞察', items: [
    { label: '运营与客服', path: '/operations', icon: Activity, permissions: ['trace:read', 'journey:read', 'contact:read', 'event:read', 'funding:reconcile'] },
    { label: '衡量分析', path: '/analytics', icon: BarChart3, permissions: ['measurement:read'] },
  ] },
]
