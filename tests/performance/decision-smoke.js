import http from 'k6/http';
import { check, fail } from 'k6';

const p99Millis = Number(__ENV.P99_MS || 30);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 500),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    http_req_duration: [`p(99)<${p99Millis}`],
    checks: ['rate>0.999'],
  },
};

const gateway = __ENV.GATEWAY_URL || 'http://host.docker.internal:8080';
const tenant = __ENV.TENANT_ID || 'retail-cn';
const organization = __ENV.ORGANIZATION_ID || 'retail-business';
const shop = __ENV.SHOP_ID || 'all-shops';
const audience = __ENV.AUDIENCE_ID || 'r1-qualified-audience';
const subject = __ENV.SUBJECT_TOKEN || 'r1-k6-qualified-subject';
const headers = {
  'Content-Type': 'application/json',
  'X-Dev-Tenant-Id': tenant,
  'X-Dev-Organization-Ids': organization,
  'X-Dev-Shop-Ids': shop,
  'X-Dev-Actor-Id': 'k6-decision',
  'X-Dev-Permissions': '*',
};

export function setup() {
  const response = http.put(
    `${gateway}/api/v1/decisions/runtime/audience-membership`,
    JSON.stringify({
      snapshotId: audience,
      subjectToken: subject,
      member: true,
      version: Date.now(),
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    }),
    { headers },
  );
  if (response.status !== 200) {
    fail(`decision load setup failed (${response.status}): ${response.body}`);
  }
  return { runId: `${Date.now().toString(36)}` };
}

export default function (data) {
  const identity = `${data.runId}-${__VU}-${__ITER}`;
  const response = http.post(
    `${gateway}/api/v1/decisions:evaluate`,
    JSON.stringify({
      requestId: `k6-request-${identity}`,
      idempotencyKey: `k6-idempotency-${identity}`,
      organizationId: organization,
      orderId: `k6-order-${identity}`,
      subjectToken: subject,
      channel: 'APP',
      occurredAt: new Date().toISOString(),
      cart: {
        currency: 'CNY',
        lines: [{
          lineId: 'line-1',
          skuId: 'k6-sku',
          shopId: shop,
          categoryId: 'home',
          brandId: 'k6-brand',
          quantity: 1,
          unitPrice: { currency: 'CNY', minorUnits: 10000 },
          floorUnitPrice: { currency: 'CNY', minorUnits: 1000 },
          shippingLine: false,
        }],
      },
      audienceSnapshotIds: [audience],
    }),
    { headers, tags: { endpoint: 'decision-evaluate' } },
  );
  check(response, {
    'decision succeeds': (value) => value.status === 200,
    'decision uses active generation': (value) => value.status === 200 && value.json('generation') > 0,
    'decision returns an applied offer': (value) => value.status === 200 && value.json('pricing.appliedOffers.0.offerId') !== undefined,
  });
}
