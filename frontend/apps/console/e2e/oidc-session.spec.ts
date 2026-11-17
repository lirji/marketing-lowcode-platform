import { expect, test, type Page } from '@playwright/test'
import { campaignFixtures } from '../src/test/fixtures/campaigns'

async function signInFixture(page: Page) {
  await page.addInitScript(() => {
    const authority = `${window.location.origin}/oidc-fixture`
    window.__MARKETING_CONFIG__ = { API_BASE_URL: '', DEMO_MODE: false, AUTH_MODE: 'OIDC', ALLOW_DEV_AUTH: false, OIDC_AUTHORITY: authority, OIDC_CLIENT_ID: 'console-fixture', OIDC_SCOPE: 'openid profile' }
    const now = Math.floor(Date.now() / 1000)
    const claims = { sub: 'operator-fixture', tenant_id: 'retail-cn', org_ids: ['retail-business'], shop_ids: ['all-shops'], permissions: ['*'] }
    sessionStorage.setItem(`oidc.user:${authority}:console-fixture`, JSON.stringify({
      access_token: `fixture.${btoa(JSON.stringify(claims))}.fixture`, token_type: 'Bearer', expires_at: now + 3600,
      profile: { iss: authority, aud: 'console-fixture', sub: 'operator-fixture', name: '测试运营', iat: now, exp: now + 3600 },
    }))
  })
  await page.route('**/api/v1/releases/page?*', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null, state: null } }))
  await page.route('**/api/v1/approvals/page?*', route => route.fulfill({ json: { items: [], hasMore: false, nextCursor: null, status: 'SUBMITTED' } }))
}

test('OIDC current-token rejection removes the protected page and its data', async ({ page }, info) => {
  await signInFixture(page)
  let rejected = false
  await page.route('**/api/v1/campaigns', route => {
    expect(route.request().headers().authorization).toMatch(/^Bearer fixture\./)
    return route.fulfill(rejected ? { status: 401, json: { type: 'about:blank', title: 'Unauthorized', status: 401 } } : { json: campaignFixtures })
  })
  await page.goto('/campaigns')
  await expect(page.getByRole('cell', { name: campaignFixtures[0].name, exact: true })).toBeVisible()
  rejected = true
  await page.getByRole('button', { name: '刷新' }).click()
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect(page.getByRole('table', { name: '营销活动' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '使用 Casdoor 登录', exact: true })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem(`oidc.user:${window.location.origin}/oidc-fixture:console-fixture`))).toBeNull()
  await page.screenshot({ path: info.outputPath('oidc-current-token-rejected.png') })
})

test('OIDC failed remote signout keeps the console signed out and explains recovery', async ({ page }, info) => {
  await signInFixture(page)
  await page.route('**/api/v1/campaigns', route => route.fulfill({ json: campaignFixtures }))
  await page.route('**/oidc-fixture/.well-known/openid-configuration', route => route.fulfill({ status: 503, json: {} }))
  await page.goto('/campaigns')
  await expect(page.getByRole('cell', { name: campaignFixtures[0].name, exact: true })).toBeVisible()
  await page.getByTitle('账户', { exact: true }).click()
  await expect(page.getByText('退出后需重新登录，未保存的修改可能丢失。', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('alert')).toContainText('已退出本机会话，但统一身份服务退出未完成')
  await expect(page.getByRole('table', { name: '营销活动' })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('oidc-signout-recovery.png') })
})
