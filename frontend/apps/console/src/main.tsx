import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './shared/auth/AuthContext'
import { ErrorBoundary } from './components/ErrorBoundary'
import { ApiProblem } from './shared/api/client'
import { reportUnknownError } from './shared/observability/report'
import { runtimeConfigError } from './shared/config/runtime'
import './styles.css'
import './feature-styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: false,
      retry: (count, error) => error instanceof ApiProblem && (error.status === 401 || error.status === 403) ? false : count < 1,
    },
    mutations: {
      retry: false,
    },
  },
})

window.addEventListener('error', (event) => {
  reportUnknownError(event.error ?? event.message, { code: 'WINDOW_ERROR' })
})
window.addEventListener('unhandledrejection', (event) => {
  reportUnknownError(event.reason, { code: 'UNHANDLED_REJECTION' })
})

const root = document.getElementById('root')!
createRoot(root).render(
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          {runtimeConfigError ? (
            <div className="auth-error" role="alert"><h1>无法启动营销中枢</h1><p>{runtimeConfigError.message}</p></div>
          ) : (
            <AuthProvider><App /></AuthProvider>
          )}
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)
