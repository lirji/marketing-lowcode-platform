import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './app/AppShell'
import { RequireAuth } from './app/RequireAuth'
import { RequirePermission } from './app/RequirePermission'
import { runtimeConfigError } from './shared/config/runtime'

const LoginPage = lazy(() => import('./features/auth/LoginPage').then((module) => ({ default: module.LoginPage })))
const AuthCallbackPage = lazy(() => import('./features/auth/AuthCallbackPage').then((module) => ({ default: module.AuthCallbackPage })))

const DashboardPage = lazy(() => import('./features/dashboard/DashboardPage').then((module) => ({ default: module.DashboardPage })))
const CampaignsPage = lazy(() => import('./features/campaign/CampaignsPage').then((module) => ({ default: module.CampaignsPage })))
const OfferDesignerPage = lazy(() => import('./features/designers/OfferDesignerPage').then((module) => ({ default: module.OfferDesignerPage })))
const AudienceBuilderPage = lazy(() => import('./features/designers/AudienceBuilderPage').then((module) => ({ default: module.AudienceBuilderPage })))
const JourneyDesignerPage = lazy(() => import('./features/designers/JourneyDesignerPage').then((module) => ({ default: module.JourneyDesignerPage })))
const DmnDesignerPage = lazy(() => import('./features/designers/DmnDesignerPage').then((module) => ({ default: module.DmnDesignerPage })))
const BenefitEditorPage = lazy(() => import('./features/designers/BenefitEditorPage').then((module) => ({ default: module.BenefitEditorPage })))
const ReferralDesignerPage = lazy(() => import('./features/referral/ReferralDesignerPage').then((module) => ({ default: module.ReferralDesignerPage })))
const ReferralOperationsPage = lazy(() => import('./features/referral/ReferralOperationsPage').then((module) => ({ default: module.ReferralOperationsPage })))
const GovernancePage = lazy(() => import('./features/governance/GovernancePage').then((module) => ({ default: module.GovernancePage })))
const ReleaseCenterPage = lazy(() => import('./features/release/ReleaseCenterPage').then((module) => ({ default: module.ReleaseCenterPage })))
const OperationsPage = lazy(() => import('./features/operations/OperationsPage').then((module) => ({ default: module.OperationsPage })))
const AnalyticsPage = lazy(() => import('./features/analytics/AnalyticsPage').then((module) => ({ default: module.AnalyticsPage })))
const AssetsPage = lazy(() => import('./features/assets/AssetsPage').then((module) => ({ default: module.AssetsPage })))
const NotFoundPage = lazy(() => import('./features/system/NotFoundPage').then((module) => ({ default: module.NotFoundPage })))

export default function App() {
  if (runtimeConfigError) {
    return (
      <div className="auth-error" role="alert">
        <h1>无法启动营销中枢</h1>
        <p>{runtimeConfigError.message}</p>
      </div>
    )
  }
  return (
    <Suspense fallback={<div className="route-loading" role="status">正在加载工作台…</div>}>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route path="auth/callback" element={<AuthCallbackPage />} />
        <Route element={<RequireAuth><AppShell /></RequireAuth>}>
          <Route index element={<RequirePermission anyOf={['campaign:read', 'measurement:read']}><DashboardPage /></RequirePermission>} />
          <Route path="campaigns" element={<RequirePermission anyOf={['campaign:read']}><CampaignsPage /></RequirePermission>} />
          <Route path="designers/offer" element={<RequirePermission anyOf={['definition:read', 'definition:write']}><OfferDesignerPage /></RequirePermission>} />
          <Route path="designers/audience" element={<RequirePermission anyOf={['audience:read', 'audience:preview', 'audience:write']}><AudienceBuilderPage /></RequirePermission>} />
          <Route path="designers/journey" element={<RequirePermission anyOf={['definition:read', 'journey:read']}><JourneyDesignerPage /></RequirePermission>} />
          <Route path="designers/dmn" element={<RequirePermission anyOf={['definition:read']}><DmnDesignerPage /></RequirePermission>} />
          <Route path="designers/benefit" element={<RequirePermission anyOf={['benefit:read', 'benefit:write']}><BenefitEditorPage /></RequirePermission>} />
          <Route path="designers/referral" element={<RequirePermission anyOf={['definition:read', 'definition:write']}><ReferralDesignerPage /></RequirePermission>} />
          <Route path="assets" element={<RequirePermission anyOf={['audience:read', 'benefit:read', 'template:read', 'audience-field:read', 'definition:read']}><AssetsPage /></RequirePermission>} />
          <Route path="governance" element={<RequirePermission anyOf={['approval:business', 'approval:finance', 'approval:compliance', 'approval:merchant', 'definition:read']}><GovernancePage /></RequirePermission>} />
          <Route path="releases" element={<RequirePermission anyOf={['release:read']}><ReleaseCenterPage /></RequirePermission>} />
          <Route path="operations" element={<RequirePermission anyOf={['trace:read', 'journey:read', 'contact:read', 'event:read', 'funding:reconcile']}><OperationsPage /></RequirePermission>} />
          <Route path="referral-operations" element={<RequirePermission anyOf={['referral:read', 'campaign:read', 'trace:read']}><ReferralOperationsPage /></RequirePermission>} />
          <Route path="analytics" element={<RequirePermission anyOf={['measurement:read']}><AnalyticsPage /></RequirePermission>} />
          <Route path="overview" element={<Navigate to="/" replace />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
