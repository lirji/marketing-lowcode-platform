import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const accountedResponses = new Rate('fault_responses_accounted');
const acceptedResponses = new Counter('fault_accepted_responses');
const backpressureResponses = new Counter('fault_backpressure_responses');
const responseDuration = new Trend('fault_response_duration', true);

export const options = {
  scenarios: {
    kafkaFault: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 500),
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 200),
      maxVUs: Number(__ENV.MAX_VUS || 5000),
    },
  },
  thresholds: {
    fault_responses_accounted: ['rate>0.999'],
    fault_accepted_responses: ['count>0'],
    fault_backpressure_responses: ['count>0'],
    fault_response_duration: [`p(99)<${Number(__ENV.P99_MS || 5000)}`],
    dropped_iterations: ['count==0'],
  },
};

const gateway = __ENV.GATEWAY_URL;
const tenant = __ENV.TENANT_ID || 'capacity-kafka-fault';
const headers = {
  'Content-Type': 'application/json',
  'X-Dev-Tenant-Id': tenant,
  'X-Dev-Organization-Ids': 'capacity-org',
  'X-Dev-Shop-Ids': 'capacity-shop',
  'X-Dev-Actor-Id': 'k6-kafka-fault',
  'X-Dev-Permissions': '*',
};
if (__ENV.AUTHORIZATION) headers.Authorization = __ENV.AUTHORIZATION;

export function setup() {
  if (!gateway) fail('GATEWAY_URL is required');
  const runId = __ENV.RUN_ID || Date.now().toString(36);
  const sourceId = `kafka-fault-${runId}`;
  const response = http.post(`${gateway}/api/v1/events/sources`, JSON.stringify({
    sourceId,
    sourceUri: `urn:marketing:k6:kafka-fault:${runId}`,
    allowedTypes: ['LOAD_TEST_EVENT'], schemaVersions: ['1.0.0'], maxLatenessSeconds: 300,
  }), { headers: { ...headers, 'Idempotency-Key': `kafka-fault-source-${runId}` } });
  if (response.status !== 201) fail(`fault source setup failed (${response.status}): ${response.body}`);
  return { runId, sourceId };
}

export default function (data) {
  const identity = `${data.runId}-${__VU}-${__ITER}`;
  const response = http.post(`${gateway}/api/v1/events`, JSON.stringify({
    eventId: `kafka-fault-event-${identity}`,
    sourceId: data.sourceId,
    eventType: 'LOAD_TEST_EVENT',
    businessKey: `kafka-fault-${identity}`,
    subjectToken: `kafka-fault-subject-${__VU}`,
    occurredAt: new Date().toISOString(), schemaVersion: '1.0.0', data: { identity },
  }), { headers });
  const accepted = response.status === 202;
  const backpressured = response.status === 503 && response.body.includes('EVENT_OUTBOX_BACKPRESSURE');
  acceptedResponses.add(accepted ? 1 : 0);
  backpressureResponses.add(backpressured ? 1 : 0);
  accountedResponses.add(accepted || backpressured);
  responseDuration.add(response.timings.duration);
  check(response, { 'request is accepted or explicitly backpressured': () => accepted || backpressured });
}
