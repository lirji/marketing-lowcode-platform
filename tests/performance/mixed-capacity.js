import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const decisionRate = Number(__ENV.DECISION_RATE);
const eventRate = Number(__ENV.EVENT_RATE);
const duration = __ENV.DURATION || '4h';
const decisionP99Millis = Number(__ENV.DECISION_P99_MS || 30);
const eventP99Millis = Number(__ENV.EVENT_P99_MS || 1000);
const allowProtection = __ENV.ALLOW_PROTECTION === 'true';
const decisionAccepted = new Counter('capacity_decision_accepted');
const eventAccepted = new Counter('capacity_event_accepted');
const decisionProtected = new Counter('capacity_decision_protected');
const eventProtected = new Counter('capacity_event_protected');
const unexpectedResponses = new Rate('capacity_unexpected_responses');

if (!Number.isInteger(decisionRate) || decisionRate < 1 || !Number.isInteger(eventRate) || eventRate < 1) {
  throw new Error('DECISION_RATE and EVENT_RATE must be positive integers');
}

const thresholds = {
  'http_req_duration{endpoint:decision-evaluate}': [`p(99)<${decisionP99Millis}`],
  'http_req_duration{endpoint:event-ingest}': [`p(99)<${eventP99Millis}`],
  checks: ['rate>0.999'],
  capacity_unexpected_responses: ['rate<0.0005'],
  dropped_iterations: ['count==0'],
};
if (allowProtection) {
  thresholds.capacity_decision_accepted = ['count>0'];
  thresholds.capacity_event_accepted = ['count>0'];
} else {
  thresholds['http_req_failed{endpoint:decision-evaluate}'] = ['rate<0.0001'];
  thresholds['http_req_failed{endpoint:event-ingest}'] = ['rate<0.0005'];
}

export const options = {
  scenarios: {
    decision: {
      executor: 'constant-arrival-rate',
      exec: 'evaluateDecision',
      rate: decisionRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.DECISION_PREALLOCATED_VUS || 200),
      maxVUs: Number(__ENV.DECISION_MAX_VUS || 5000),
    },
    event: {
      executor: 'constant-arrival-rate',
      exec: 'ingestEvent',
      rate: eventRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Number(__ENV.EVENT_PREALLOCATED_VUS || 200),
      maxVUs: Number(__ENV.EVENT_MAX_VUS || 5000),
    },
  },
  thresholds,
};

const gateway = __ENV.GATEWAY_URL;
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
  'X-Dev-Actor-Id': 'k6-capacity',
  'X-Dev-Permissions': '*',
};
if (__ENV.AUTHORIZATION) headers.Authorization = __ENV.AUTHORIZATION;

export function setup() {
  if (!gateway) fail('GATEWAY_URL is required');
  const runId = __ENV.RUN_ID || Date.now().toString(36);
  const sourceId = `k6-capacity-${runId}`;
  const source = http.post(`${gateway}/api/v1/events/sources`, JSON.stringify({
    sourceId,
    sourceUri: `urn:marketing:k6:capacity:${runId}`,
    allowedTypes: ['LOAD_TEST_EVENT'],
    schemaVersions: ['1.0.0'],
    maxLatenessSeconds: 300,
  }), { headers: { ...headers, 'Idempotency-Key': `capacity-source-${runId}` } });
  if (source.status !== 201) fail(`event source setup failed (${source.status}): ${source.body}`);

  const membership = http.put(`${gateway}/api/v1/decisions/runtime/audience-membership`, JSON.stringify({
    snapshotId: audience,
    subjectToken: subject,
    member: true,
    version: Date.now(),
    expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
  }), { headers });
  if (membership.status !== 200) {
    fail(`decision membership setup failed (${membership.status}): ${membership.body}`);
  }
  return { runId, sourceId };
}

export function evaluateDecision(data) {
  const identity = `${data.runId}-decision-${__VU}-${__ITER}`;
  const response = http.post(`${gateway}/api/v1/decisions:evaluate`, JSON.stringify({
    requestId: `capacity-request-${identity}`,
    idempotencyKey: `capacity-idempotency-${identity}`,
    organizationId: organization,
    orderId: `capacity-order-${identity}`,
    subjectToken: subject,
    channel: 'APP',
    occurredAt: new Date().toISOString(),
    cart: {
      currency: 'CNY',
      lines: [{
        lineId: 'line-1', skuId: 'capacity-sku', shopId: shop, categoryId: 'home',
        brandId: 'capacity-brand', quantity: 1,
        unitPrice: { currency: 'CNY', minorUnits: 10000 },
        floorUnitPrice: { currency: 'CNY', minorUnits: 1000 }, shippingLine: false,
      }],
    },
    audienceSnapshotIds: [audience],
  }), { headers, tags: { endpoint: 'decision-evaluate' } });
  const accepted = response.status === 200 && response.json('generation') > 0;
  const protectedResponse = allowProtection && response.status === 429
    && response.body.includes('TENANT_RATE_LIMITED');
  decisionAccepted.add(accepted ? 1 : 0);
  decisionProtected.add(protectedResponse ? 1 : 0);
  unexpectedResponses.add(!(accepted || protectedResponse));
  check(response, {
    'decision succeeds or burst is explicitly throttled': () => accepted || protectedResponse,
  });
}

export function ingestEvent(data) {
  const identity = `${data.runId}-event-${__VU}-${__ITER}`;
  const response = http.post(`${gateway}/api/v1/events`, JSON.stringify({
    eventId: `capacity-event-${identity}`,
    sourceId: data.sourceId,
    eventType: 'LOAD_TEST_EVENT',
    businessKey: `capacity-${identity}`,
    subjectToken: `capacity-subject-${__VU}`,
    occurredAt: new Date().toISOString(),
    schemaVersion: '1.0.0',
    data: { runId: data.runId, sequence: `${__VU}-${__ITER}` },
  }), { headers, tags: { endpoint: 'event-ingest' } });
  const accepted = response.status === 202 && response.json('status') === 'ACCEPTED';
  const protectedResponse = allowProtection && (response.status === 429
    && response.body.includes('TENANT_RATE_LIMITED') || response.status === 503
    && response.body.includes('EVENT_OUTBOX_BACKPRESSURE'));
  eventAccepted.add(accepted ? 1 : 0);
  eventProtected.add(protectedResponse ? 1 : 0);
  unexpectedResponses.add(!(accepted || protectedResponse));
  check(response, {
    'event is accepted or burst is explicitly protected': () => accepted || protectedResponse,
  });
}
