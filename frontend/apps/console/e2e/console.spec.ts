import { expect, test } from '@playwright/test'

test('operator can enter governed campaign creation', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '早上好，今天的增长脉搏稳定。' })).toBeVisible()
  await page.getByRole('link', { name: /创建营销活动/ }).click()
  await expect(page.getByRole('dialog', { name: '创建营销活动' })).toBeVisible()
  await page.getByLabel('活动名称').fill('双11冰洗焕新')
  await page.getByLabel('可衡量目标').fill('提升 PLUS 家电用户确认转化率 8%')
  await page.getByRole('button', { name: '创建并进入设计' }).click()
  await expect(page.getByText('双11冰洗焕新')).toBeVisible()
})

test('release action requires explicit evidence and stays inert in demo mode', async ({ page }) => {
  await page.goto('/releases')
  await expect(page.getByRole('heading', { name: '发布中心' })).toBeVisible()
  await expect(page.getByText('演示数据')).toBeVisible()
  await expect(page.getByRole('button', { name: /推进灰度/ })).toBeDisabled()
})

test('live release confirm posts to the control API', async ({ page }) => {
  await page.route('**/api/v1/releases', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([{
          manifest: { manifestId: 'man-1', cell: 'cn-east-a', generation: 12, namespace: 'main', environment: 'prod', runtime: 'decision', artifacts: [{}], canaryBasisPoints: 1500, signature: 'sig' },
          state: 'STAGED',
          readyReplicas: 2,
          readyCapacity: 100,
        }]),
      })
      return
    }
    await route.continue()
  })
  await page.route('**/api/v1/releases/man-1:activate', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['idempotency-key']).toBeTruthy()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        manifest: { manifestId: 'man-1', cell: 'cn-east-a', generation: 12, artifacts: [] },
        state: 'ACTIVE',
        readyReplicas: 2,
        readyCapacity: 100,
      }),
    })
  })
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = {
      API_BASE_URL: '',
      DEMO_MODE: false,
      AUTH_MODE: 'DEV',
      ALLOW_DEV_AUTH: true,
      OIDC_AUTHORITY: 'http://localhost:8180/realms/marketing',
      OIDC_CLIENT_ID: 'marketing-console',
      OIDC_SCOPE: 'openid profile email',
    }
  })
  await page.goto('/releases')
  await expect(page.getByRole('button', { name: /推进灰度/ })).toBeEnabled({ timeout: 10_000 })
  await page.getByRole('button', { name: /推进灰度/ }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByPlaceholder('APR-7201 / 原因').fill('APR-7201 / canary')
  await page.getByText('我已核对作用范围、流量和回滚目标').click()
  await page.getByRole('button', { name: '确认执行' }).click()
  await expect(page.getByText(/灰度\/激活已提交/)).toBeVisible()
})

test('unauthorised identity cannot open release actions', async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = {
      API_BASE_URL: '',
      DEMO_MODE: false,
      AUTH_MODE: 'DEV',
      ALLOW_DEV_AUTH: true,
    }
  })
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Forbidden', status: 403, detail: 'permission is required: release:read', type: 'about:blank', code: 'PERMISSION_DENIED' }) })
  })
  await page.goto('/releases')
  await expect(page.getByRole('alert')).toContainText('发布列表加载失败')
  await expect(page.getByRole('alert')).toContainText('permission is required')
})
