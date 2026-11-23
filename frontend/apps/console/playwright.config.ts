import { defineConfig, devices } from '@playwright/test'

// 并行的隔离验收可独占端口，避免另一轮 Playwright 收尾时停掉本轮浏览器依赖的 Vite。
const isolatedPort = process.env.MARKETING_CONSOLE_E2E_PORT
const port = Number(isolatedPort ?? 4173)
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('无效的控制台验收端口')
const localOrigin = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [['html', { open: 'never' }], ['github']] : 'list',
  use: {
    baseURL: localOrigin,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `pnpm dev --host 127.0.0.1 --port ${port} --strictPort`,
    url: localOrigin,
    reuseExistingServer: !process.env.CI && !isolatedPort,
    timeout: 120_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } },
  ],
})
