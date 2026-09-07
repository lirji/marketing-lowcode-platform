import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const hotRate = Number(__ENV.HOT_TENANT_RATE);
const controlRate = Number(__ENV.CONTROL_TENANT_RATE);
const controlP99Millis = Number(__ENV.CONTROL_P99_MS || 1000);
const controlFailures = new Rate('control_tenant_failures');
const controlLatency = new Trend('control_tenant_duration', true);
const hotResponsesAccounted = new Rate('hot_tenant_responses_accounted');

if (!Number.isInteger(hotRate) || hotRate < 1 || !Number.isInteger(controlRate) || controlRate < 1) {
  throw new Error('HOT_TENANT_RATE and CONTROL_TENANT_RATE must be positive integers');
}

export const options = {
  scenarios: {
    noisyTenant: {
      executor: 'constant-arrival-rate', exec: 'sendHot', rate: hotRate, timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: Number(__ENV.HOT_PREALLOCATED_VUS || 300),
      maxVUs: Number(__ENV.HOT_MAX_VUS || 6000),
    },
    controlTenant: {
      executor: 'constant-arrival-rate', exec: 'sendControl', rate: controlRate, timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: Number(__ENV.CONTROL_PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.CONTROL_MAX_VUS || 1000),
    },
  },
  thresholds: {
    control_tenant_failures: ['rate<0.0005'],
    control_tenant_duration: [`p(99)<${controlP99Millis}`],
    hot_tenant_responses_accounted: ['rate>0.999'],
    dropped_iterations: ['count==0'],
  },
};

const gateway = __ENV.GATEWAY_URL;
const hotTenant = __ENV.HOT_TENANT_ID || 'capacity-hot-tenant';
const controlTenant = __ENV.CONTROL_TENANT_ID || 'capacity-control-tenant';

function headers(tenant) {
  const values = {
    'Content-Type': 'application/json',
    'X-Dev-Tenant-Id': tenant,
    'X-Dev-Organization-Ids': 'capacity-org',
    'X-Dev-Shop-Ids': 'capacity-shop',
    'X-Dev-Actor-Id': 'k6-tenant-isolation',
    'X-Dev-Permissions': '*',
  };
  const authorization = tenant === hotTenant ? __ENV.HOT_AUTHORIZATION : __ENV.CONTROL_AUTHORIZATION;
  if (authorization) values.Authorization = authorization;
  return values;
}

function register(tenant, runId) {
  const sourceId = `isolation-${runId}`;
  const response = http.post(`${gateway}/api/v1/events/sources`, JSON.stringify({
    sourceId,
    sourceUri: `urn:marketing:k6:isolation:${tenant}:${runId}`,
    allowedTypes: ['LOAD_TEST_EVENT'], schemaVersions: ['1.0.0'], maxLatenessSeconds: 300,
  }), { headers: { ...headers(tenant), 'Idempotency-Key': `isolation-source-${runId}` } });
  if (response.status !== 201) fail(`${tenant} source setup failed (${response.status}): ${response.body}`);
  return sourceId;
}

export function setup() {
  if (!gateway) fail('GATEWAY_URL is required');
  const runId = __ENV.RUN_ID || Date.now().toString(36);
  return {
    runId,
    hotSource: register(hotTenant, `${runId}-hot`),
    controlSource: register(controlTenant, `${runId}-control`),
  };
}

function send(tenant, sourceId, identity) {
  return http.post(`${gateway}/api/v1/events`, JSON.stringify({
    eventId: `isolation-event-${identity}`,
    sourceId,
    eventType: 'LOAD_TEST_EVENT',
    businessKey: `isolation-${identity}`,
    subjectToken: `isolation-subject-${__VU}`,
    occurredAt: new Date().toISOString(),
    schemaVersion: '1.0.0', data: { identity },
  }), { headers: headers(tenant) });
}

export function sendHot(data) {
  const response = send(hotTenant, data.hotSource, `${data.runId}-hot-${__VU}-${__ITER}`);
  const explicitProtection = response.status === 429 && response.body.includes('TENANT_RATE_LIMITED')
    || response.status === 503 && response.body.includes('EVENT_OUTBOX_BACKPRESSURE');
  hotResponsesAccounted.add(response.status === 202 || explicitProtection);
  check(response, { 'hot response is explicit': (value) => value.status === 202 || explicitProtection });
}

export function sendControl(data) {
  const response = send(controlTenant, data.controlSource, `${data.runId}-control-${__VU}-${__ITER}`);
  controlFailures.add(response.status !== 202);
  controlLatency.add(response.timings.duration);
  check(response, { 'control tenant remains accepted': (value) => value.status === 202 });
}
