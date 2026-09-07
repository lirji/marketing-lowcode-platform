import http from 'k6/http';
import { check, fail } from 'k6';

const p99Millis = Number(__ENV.P99_MS || 200);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 500),
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 1000),
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
const headers = {
  'Content-Type': 'application/json',
  'X-Dev-Tenant-Id': tenant,
  'X-Dev-Organization-Ids': __ENV.ORGANIZATION_ID || 'retail-business',
  'X-Dev-Shop-Ids': __ENV.SHOP_ID || 'all-shops',
  'X-Dev-Actor-Id': 'k6-event-ingest',
  'X-Dev-Permissions': '*',
};

export function setup() {
  const runId = __ENV.RUN_ID || Date.now().toString(36);
  const sourceId = `k6-source-${runId}`;
  const response = http.post(
    `${gateway}/api/v1/events/sources`,
    JSON.stringify({
      sourceId,
      sourceUri: `urn:marketing:k6:${runId}`,
      allowedTypes: ['LOAD_TEST_EVENT'],
      schemaVersions: ['1.0.0'],
      maxLatenessSeconds: 300,
    }),
    { headers: { ...headers, 'Idempotency-Key': `event-source-${runId}` } },
  );
  if (response.status !== 201) {
    fail(`event load setup failed (${response.status}): ${response.body}`);
  }
  return { runId, sourceId };
}

export default function (data) {
  const identity = `${data.runId}-${__VU}-${__ITER}`;
  const response = http.post(
    `${gateway}/api/v1/events`,
    JSON.stringify({
      eventId: `k6-event-${identity}`,
      sourceId: data.sourceId,
      eventType: 'LOAD_TEST_EVENT',
      businessKey: `load-${identity}`,
      subjectToken: `subject-${__VU}`,
      occurredAt: new Date().toISOString(),
      schemaVersion: '1.0.0',
      data: { runId: data.runId, sequence: `${__VU}-${__ITER}` },
    }),
    { headers, tags: { endpoint: 'event-ingest' } },
  );
  check(response, {
    'event durably accepted': (value) => value.status === 202 && value.json('status') === 'ACCEPTED',
    'receipt is assigned': (value) => value.status === 202 && Boolean(value.json('receiptId')),
  });
}
