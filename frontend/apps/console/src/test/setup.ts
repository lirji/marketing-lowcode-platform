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
  BENEFIT_CONSOLE_ORIGIN: '',
  RISK_CONSOLE_ORIGIN: '',
}

afterEach(cleanup)

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, 'ResizeObserver', { value: ResizeObserverStub, writable: true })

// JSDOM没有布局/指针捕获；补齐可访问弹层依赖的浏览器API，行为由真实浏览器测试覆盖。
if (!window.PointerEvent) window.PointerEvent = MouseEvent as typeof PointerEvent
HTMLElement.prototype.hasPointerCapture = () => false
HTMLElement.prototype.setPointerCapture = () => {}
HTMLElement.prototype.releasePointerCapture = () => {}
HTMLElement.prototype.scrollIntoView = () => {}
