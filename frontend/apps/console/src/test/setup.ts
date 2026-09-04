import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

window.__MARKETING_CONFIG__ = {
  API_BASE_URL: '',
  DEMO_MODE: true,
  AUTH_MODE: 'DEV',
  ALLOW_DEV_AUTH: true,
  OIDC_AUTHORITY: 'http://localhost:8180/realms/marketing',
  OIDC_CLIENT_ID: 'marketing-console',
  OIDC_SCOPE: 'openid profile email',
}

afterEach(cleanup)

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, 'ResizeObserver', { value: ResizeObserverStub, writable: true })
