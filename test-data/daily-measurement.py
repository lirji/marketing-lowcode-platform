#!/usr/bin/env python3
"""Seed and verify daily measurement fixtures in the local retail-cn demo."""

import argparse
from collections import defaultdict
from datetime import datetime, time, timedelta, timezone
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


BEIJING = timezone(timedelta(hours=8))
TENANT = 'retail-cn'
CAMPAIGN = 'mock-cmp-ha-2026'
FIXTURE = 'mock-daily-measurement-v1'
EVENT_PREFIX = f'{FIXTURE}-'
SEED_SOURCE = 'mock.seed.retail-cn'
SEED_OFFSET = 6
# Synthetic offsets have their own range and are tied to the exact demo watermark.
OFFSET_BASE = 10**15
STATE_DIR = Path.home() / '.local/share/marketing-daily-measurement'
STATE_FILE = STATE_DIR / 'original-watermark.json'
HEADERS = {
    'X-Dev-Tenant-Id': TENANT,
    'X-Dev-Organization-Ids': 'retail-business',
    'X-Dev-Shop-Ids': 'all-shops',
    'X-Dev-Actor-Id': FIXTURE,
    'X-Dev-Permissions': 'campaign:read,measurement:read,measurement:ingest,measurement:watermark',
}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


LOCAL_HTTP = build_opener(ProxyHandler({}), NoRedirect())


def docker(*args, input_text=None):
    return subprocess.check_output(['docker', *args], text=True, input=input_text, timeout=30).strip()


def one_container(project, service):
    matches = docker('ps', '-q', '--filter', f'label=com.docker.compose.project={project}',
                     '--filter', f'label=com.docker.compose.service={service}').splitlines()
    if len(matches) != 1:
        raise ValueError(f'expected one running {project}/{service} container')
    return json.loads(docker('inspect', matches[0]))[0]


def container_env(container):
    return dict(entry.split('=', 1) for entry in container['Config']['Env'])


def local_target(project):
    endpoint = os.environ.get('DOCKER_HOST') or docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')
    if not endpoint.startswith('unix://'):
        raise ValueError('a local Unix-socket Docker engine is required')
    gateway = one_container(project, 'edge-gateway')
    gateway_env = container_env(gateway)
    port_bindings = gateway['NetworkSettings']['Ports'].get('8080/tcp') or []
    if (gateway_env.get('MARKETING_SECURITY_MODE') != 'DEV'
            or gateway_env.get('MARKETING_DEV_HEADERS_ENABLED') != 'true'
            or len(port_bindings) != 1 or port_bindings[0]['HostIp'] != '127.0.0.1'):
        raise ValueError('expected a loopback-bound DEV gateway with DEV headers enabled')
    measurement = one_container(project, 'measurement-service')
    mysql = one_container('dev-infra', 'mysql84')
    jdbc = container_env(measurement).get('MARKETING_DB_URL', '').split('?', 1)[0]
    mysql_networks = mysql['NetworkSettings']['Networks']
    known_urls = {
        f'jdbc:mysql://{alias}:3306/marketing_measurement'
        for network in mysql_networks.keys() & measurement['NetworkSettings']['Networks'].keys()
        for alias in mysql_networks[network].get('Aliases') or []
    }
    if jdbc not in known_urls:
        raise ValueError('measurement must use the local dev-infra MySQL database')
    return f'http://127.0.0.1:{int(port_bindings[0]["HostPort"])}', mysql['Id']


def api(origin, path, *, body=None, **params):
    url = f'{origin}/api/v1/{path}' + (f'?{urlencode(params)}' if params else '')
    data = json.dumps(body).encode() if body is not None else None
    try:
        with LOCAL_HTTP.open(Request(url, data=data, headers={**HEADERS, 'Content-Type': 'application/json'}),
                             timeout=10) as response:
            return json.load(response)
    except HTTPError as exc:
        with exc:
            raise ValueError(f'{exc.code} on {path}: {exc.read(400).decode(errors="replace")}') from None


def sql(mysql, statement):
    return docker('exec', '-i', mysql, 'sh', '-ec',
                  'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -h127.0.0.1 -uroot -NB marketing_measurement',
                  input_text=statement).splitlines()


def watermark(mysql):
    rows = sql(mysql, f"""
        SELECT c.partition_count, w.partition_id, w.source_offset, w.complete_through_epoch_ms, w.updated_at
        FROM mk_projection_watermark_config c JOIN mk_projection_partition_watermark w USING (tenant_id, projection_name)
        WHERE c.tenant_id = '{TENANT}' AND c.projection_name = 'dashboard';
    """)
    if len(rows) != 1 or rows[0].split('\t')[:2] != ['1', '0']:
        raise ValueError('expected the existing single-partition demo dashboard watermark')
    _, _, offset, millis, updated = rows[0].split('\t')
    return int(offset), int(millis), updated


def fixture_row(alias):
    return (f"{alias}.event_id LIKE '{EVENT_PREFIX}%' AND "
            f"JSON_UNQUOTE(JSON_EXTRACT({alias}.attributes_json, '$.fixture')) = '{FIXTURE}'")


def require_demo_projection(mysql):
    rows = sql(mysql, f"""
        SELECT source_topic FROM mk_dashboard_projection_delta d
        WHERE d.tenant_id = '{TENANT}' AND NOT (
            d.source_topic IN ('{SEED_SOURCE}', '{SEED_SOURCE}.extra') OR (
                d.source_topic = 'direct-api' AND EXISTS (
                    SELECT 1 FROM mk_fact f WHERE f.tenant_id = d.tenant_id
                    AND f.event_id = d.source_event_id AND {fixture_row('f')}
                )
            )
        ) LIMIT 1;
        SELECT COUNT(*) FROM mk_dashboard_projection_delta
        WHERE tenant_id = '{TENANT}' AND source_topic = '{SEED_SOURCE}' AND source_partition = 0 AND source_offset = {SEED_OFFSET};
    """)
    if rows != ['1']:
        raise ValueError('dashboard projection is outside the known retail-cn demo scope')


def utc(instant):
    return instant.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def generate(through, days):
    today = through.astimezone(BEIJING).date()
    start = today - timedelta(days=days - 1)
    facts = []
    for day_index in range(days):
        day = start + timedelta(days=day_index)
        for hour in (0, 8, 16):
            occurred = datetime.combine(day, time(hour), BEIJING)
            if occurred >= through:
                break
            event = f'{EVENT_PREFIX}{day:%Y%m%d}-{hour:02d}'
            # 金额由日期和时段决定，既有日间波动，也能在失败后用相同事件 ID 安全重试。
            revenue = 16000 + ((day.toordinal() * 173 + hour * 839) % 191) * 100
            cost = 900 + ((day.toordinal() + hour) % 17) * 100
            facts.append({'eventId': event, 'type': 'CONVERSION', 'businessKey': event,
                          'subjectToken': event, 'occurredAt': utc(occurred), 'ingestedAt': utc(through),
                          'schemaVersion': '1.0.0', 'correctionOf': '',
                          'attributes': {'campaignId': CAMPAIGN, 'revenueMinor': str(revenue),
                                         'costMinor': str(cost), 'fixture': FIXTURE}})
    return datetime.combine(start, time(), BEIJING), facts


def projected(mysql, start):
    rows = sql(mysql, f"""
        SELECT f.event_id, f.occurred_at, f.revenue_minor, f.cost_minor
        FROM mk_fact f JOIN mk_dashboard_projection_delta d
          ON d.tenant_id = f.tenant_id AND d.source_event_id = f.event_id
          AND d.root_event_id = f.event_id AND d.source_topic = 'direct-api'
          AND d.campaign_id = '{CAMPAIGN}' AND d.revision_no = 1 AND d.count_delta = 1
          AND d.fact_type = 'CONVERSION' AND d.occurred_at = f.occurred_at
          AND d.revenue_delta_minor = f.revenue_minor AND d.cost_delta_minor = f.cost_minor
        WHERE f.tenant_id = '{TENANT}' AND f.fact_type = 'CONVERSION' AND NOT f.corrected_value
          AND f.occurred_at >= '{start.strftime('%Y-%m-%dT%H:%M:%S')}.000000000Z' AND {fixture_row('f')};
    """)
    return {event: (datetime.fromisoformat(at.replace('Z', '+00:00')), revenue, cost)
            for event, at, revenue, cost in (row.split('\t') for row in rows)}


def fixture_totals(facts, observed):
    expected = defaultdict(lambda: [0, 0, 0])
    for fact in facts:
        occurred = datetime.fromisoformat(fact['occurredAt'].replace('Z', '+00:00'))
        amounts = fact['attributes']
        if observed.get(fact['eventId']) != (occurred, amounts['revenueMinor'], amounts['costMinor']):
            raise ValueError(f'missing or incorrect fixture projection: {fact["eventId"]}')
        bucket = expected[occurred.astimezone(BEIJING).date()]
        bucket[0] += int(amounts['revenueMinor'])
        bucket[1] += int(amounts['costMinor'])
        bucket[2] += 1
    return expected


def verify(origin, start, through, expected):
    params = {'from': utc(start), 'to': utc(through), 'campaignId': CAMPAIGN}
    dashboard = api(origin, 'measurements/dashboard', **params)
    series = api(origin, 'measurements/series', granularity='day', dayOffset='+08:00', **params)
    if (dashboard['campaignId'] != CAMPAIGN or series['campaignId'] != CAMPAIGN or series['dayOffset'] != '+08:00'
            or datetime.fromisoformat(dashboard['completeThrough'].replace('Z', '+00:00')) < through):
        raise ValueError('measurement API did not confirm the covered fixture window')
    points = {datetime.fromisoformat(p['at'].replace('Z', '+00:00')).astimezone(BEIJING).date(): p
              for p in series['points']}
    for day, amounts in expected.items():
        point = points[day]
        if any(point[key] < amount for key, amount in zip(('revenueMinor', 'costMinor', 'conversions'), amounts)):
            raise ValueError(f'fixture amounts missing from API series on {day}')
    for key, total in [('revenueMinor', dashboard['attributedRevenueMinor']),
                       ('costMinor', dashboard['fundingCostMinor']),
                       ('conversions', dashboard['counts'].get('CONVERSION', 0))]:
        if sum(p[key] for p in points.values()) != total:
            raise ValueError(f'daily series does not reconcile with dashboard: {key}')


def original_watermark():
    millis, updated = json.loads(STATE_FILE.read_text())
    datetime.fromisoformat(updated.replace('Z', '+00:00'))
    return int(millis), updated


def cleanup(mysql, current):
    offset, millis, _ = current
    restore = ''
    if offset == OFFSET_BASE + millis:
        previous_millis, previous_updated = original_watermark()
        restore = f"""
            UPDATE mk_projection_partition_watermark SET source_offset = {SEED_OFFSET},
                complete_through_epoch_ms = {previous_millis}, updated_at = '{previous_updated}'
            WHERE tenant_id = '{TENANT}' AND projection_name = 'dashboard' AND partition_id = 0
                AND source_offset = {offset} AND complete_through_epoch_ms = {millis};
        """
    sql(mysql, f"""
        START TRANSACTION;
        DELETE a FROM mk_attribution_credit a JOIN mk_fact f
          ON f.tenant_id = a.tenant_id AND f.event_id = a.conversion_event_id
        WHERE f.tenant_id = '{TENANT}' AND {fixture_row('f')};
        DELETE d FROM mk_dashboard_projection_delta d JOIN mk_fact f
          ON f.tenant_id = d.tenant_id AND f.event_id = d.source_event_id AND f.event_id = d.root_event_id
        WHERE f.tenant_id = '{TENANT}' AND d.source_topic = 'direct-api' AND {fixture_row('f')};
        DELETE f FROM mk_fact f WHERE f.tenant_id = '{TENANT}' AND {fixture_row('f')};
        {restore}
        COMMIT;
    """)
    print('Removed daily measurement fixtures from the local retail-cn demo.')


def run(args):
    origin, mysql = local_target(args.project)
    require_demo_projection(mysql)
    offset, millis, updated = current = watermark(mysql)
    if offset not in (SEED_OFFSET, OFFSET_BASE + millis):
        raise ValueError('dashboard watermark is owned by another writer')
    if args.command == 'cleanup':
        cleanup(mysql, current)
        return
    writing = args.command == 'init'
    through = (datetime.now(timezone.utc).replace(microsecond=0) if writing
               else datetime.fromtimestamp(millis / 1000, timezone.utc))
    through_ms = int(through.timestamp()) * 1000
    start, facts = generate(through, args.days)
    start_utc = start.astimezone(timezone.utc)
    added = 0
    if writing:
        campaigns = api(origin, 'campaigns')
        if not any(campaign['id'] == CAMPAIGN for campaign in campaigns):
            raise ValueError(f'missing demo campaign: {CAMPAIGN}')
        if through_ms < millis:
            raise ValueError('local clock is behind the demo watermark')
        if offset == SEED_OFFSET:
            temporary = STATE_FILE.with_suffix('.tmp')
            temporary.write_text(json.dumps([millis, updated]) + '\n')
            temporary.replace(STATE_FILE)
        else:
            original_watermark()
        existing = projected(mysql, start_utc)
        for fact in facts:
            if fact['eventId'] in existing:
                continue
            receipt = api(origin, 'measurements/facts', body=fact)
            if receipt['eventId'] != fact['eventId']:
                raise ValueError('measurement fact receipt does not match the fixture event')
            added += not receipt['duplicate']
    elif offset != OFFSET_BASE + millis:
        raise ValueError('daily fixtures have not advanced the dashboard watermark')
    expected = fixture_totals(facts, projected(mysql, start_utc))
    if writing and through_ms > millis:
        # 逐笔确认事实和投影后才推进演示水位，且不声明覆盖未来时间。
        require_demo_projection(mysql)
        if watermark(mysql) != current:
            raise ValueError('demo watermark changed during fixture ingestion; retry')
        api(origin, 'measurements/watermarks', body={
            'projectionName': 'dashboard', 'partitionId': 0, 'partitionCount': 1,
            'sourceOffset': OFFSET_BASE + through_ms, 'completeThrough': utc(through),
        })
    verify(origin, start, through, expected)
    print(json.dumps({'tenant': TENANT, 'campaign': CAMPAIGN, 'added': added,
                      'verifiedFacts': len(facts), 'verifiedDays': len(expected),
                      'completeThrough': utc(through)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('init', 'verify', 'cleanup'))
    parser.add_argument('--project', default='marketing-r1', help='local Compose project')
    parser.add_argument('--days', type=int, choices=range(1, 31), default=30, metavar='1–30')
    parser.add_argument('--confirm-cleanup', action='store_true')
    args = parser.parse_args()
    if args.command == 'cleanup' and not args.confirm_cleanup:
        parser.error('cleanup requires --confirm-cleanup')
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (STATE_DIR / 'run.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        run(args)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f'Daily measurement fixtures: {error}', file=sys.stderr)
        sys.exit(1)
