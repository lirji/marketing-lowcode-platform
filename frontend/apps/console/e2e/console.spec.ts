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

test('operations awards tab is read-only', async ({ page }) => {
  await page.goto('/operations?tab=awards')
  await expect(page.getByRole('tab', { name: '发放' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '发放指令' })).toBeVisible()
  await expect(page.getByText('演示模式不查询真实发放指令')).toBeVisible()
  await expect(page.getByRole('button', { name: /提交发奖|新建 AwardIntent/ })).toHaveCount(0)
})

test('live awards tab shows risk-blocked reason without a submit form', async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = {
      API_BASE_URL: '',
      DEMO_MODE: false,
      AUTH_MODE: 'DEV',
      ALLOW_DEV_AUTH: true,
      OIDC_AUTHORITY: 'http://localhost:8180/realms/marketing',
      OIDC_CLIENT_ID: 'marketing-console',
      OIDC_SCOPE: 'openid profile email',
      BENEFIT_CONSOLE_ORIGIN: '',
      RISK_CONSOLE_ORIGIN: '',
    }
  })
  await page.route('**/api/v1/campaigns', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ id: 'CMP-1', name: '家电活动', objective: '转化', status: 'ACTIVE', createdAt: '2026-09-01T00:00:00Z' }]),
    })
  })
  await page.route('**/api/v1/award-intents**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{
        intentId: 'ai-blocked',
        sourceSystem: 'drools-activity',
        sourceRequestId: 'src-blocked-1',
        campaignId: 'CMP-1',
        definitionVersion: 3,
        subjectHash: 'b'.repeat(64),
        deliveryMode: 'CENTER',
        status: 'RISK_BLOCKED',
        deliveryResult: null,
        riskAction: 'REJECT',
        riskReason: 'HIT_VELOCITY_LIMIT',
        riskDecisionId: 'dec-9',
        attempts: 0,
        benefitOrderNo: null,
        lastError: '',
        createdAt: '2026-09-05T02:00:00Z',
        updatedAt: '2026-09-05T02:00:00Z',
        sentAt: null,
      }]),
    })
  })
  await page.goto('/operations?tab=awards&campaignId=CMP-1')
  await expect(page.getByText('HIT_VELOCITY_LIMIT')).toBeVisible()
  await expect(page.getByText('风控拒绝，未写出发放指令')).toBeVisible()
  await expect(page.getByRole('button', { name: /提交发奖|新建 AwardIntent/ })).toHaveCount(0)
})

test('benefit editor shows the SKU binding region', async ({ page }) => {
  await page.goto('/designers/benefit')
  await expect(page.getByRole('heading', { name: '绑定权益模板' })).toBeVisible()
  await expect(page.getByText('演示模式不绑定真实 SKU')).toBeVisible()
  const actions = page.locator('.heading-actions')
  await expect(actions.getByRole('button', { name: '保存策略' })).toBeVisible()
  const box = await actions.boundingBox()
  const viewport = page.viewportSize()
  expect(box).toBeTruthy()
  expect(viewport).toBeTruthy()
  expect(box!.x + box!.width).toBeLessThanOrEqual((viewport?.width ?? 0) + 1)
})

test('live benefit editor SKU picker stays on-screen', async ({ page }) => {
  await page.addInitScript(() => {
    window.__MARKETING_CONFIG__ = {
      API_BASE_URL: '',
      DEMO_MODE: false,
      AUTH_MODE: 'DEV',
      ALLOW_DEV_AUTH: true,
      OIDC_AUTHORITY: 'http://localhost:8180/realms/marketing',
      OIDC_CLIENT_ID: 'marketing-console',
      OIDC_SCOPE: 'openid profile email',
      BENEFIT_CONSOLE_ORIGIN: '',
    }
  })
  await page.route('**/api/v1/benefits', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })
  await page.route('**/api/v1/funding/accounts', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })
  await page.route('**/api/v1/benefit-skus**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{
        skuId: 'sku-active',
        benefitType: 'COUPON',
        faceValueMinor: 8000,
        currency: 'CNY',
        status: 'ACTIVE',
        enabled: true,
        validityType: 'RELATIVE',
        relativeDays: 7,
        usableWeekdays: [1, 2, 3, 4, 5],
        version: 1,
      }]),
    })
  })
  await page.goto('/designers/benefit')
  const picker = page.getByLabel('已投放 SKU')
  await expect(picker).toBeVisible()
  await picker.scrollIntoViewIfNeeded()
  const box = await picker.boundingBox()
  const viewport = page.viewportSize()
  expect(box).toBeTruthy()
  expect(viewport).toBeTruthy()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual((viewport?.width ?? 0) + 1)
  await expect(page.getByRole('button', { name: '保存策略' })).toBeVisible()
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
