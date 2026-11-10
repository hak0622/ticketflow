#!/usr/bin/env python3
"""User-assisted real Toss TEST cancellation. No manual Worker calls or terminal-state writes."""
import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import traceback
import urllib.request
import uuid
import zipfile

from run import Environment, ROOT, command


def main():
    meta = json.loads((ROOT / 'test-results/toss-visible/latest.json').read_text())
    assert meta['project'].startswith('tfperf-toss-visible-')
    folder = ROOT / 'test-results/toss-live-cancel' / time.strftime('%Y%m%d-%H%M%S')
    folder.mkdir(parents=True)
    gates = folder / 'gates'
    gates.mkdir()
    secret = next(line.split('=', 1)[1].strip().strip('\"\'') for line in (ROOT / '.env').read_text().splitlines()
                  if line.startswith('TOSS_SECRET_KEY='))
    assert secret.startswith('test_')
    os.environ['TF_VISIBLE_TOSS_SECRET'] = secret
    sources = {str(f.relative_to(ROOT)): hashlib.sha256(f.read_bytes()).hexdigest()
               for f in (ROOT / 'src/main').rglob('*') if f.is_file()}
    (folder / 'source-before.json').write_text(json.dumps(sources, indent=2))
    mounted = command(['docker', 'inspect', '--format', '{{range .Mounts}}{{if eq .Destination "/app/app.jar"}}{{.Source}}{{end}}{{end}}',
                       meta['project'] + '-app-1'])[0].strip()
    assert Path(mounted).is_file()
    normal = folder / 'normal-app.jar'
    shutil.copy2(mounted, normal)
    fault = folder / 'fault-app.jar'
    shutil.copy2(normal, fault)
    with zipfile.ZipFile(normal) as archive:
        assert not any('TossLiveCancelProbe' in name for name in archive.namelist())
    classes = list((ROOT / 'build/classes/java/test/studying/blog/fault').glob('TossLiveCancelProbe*.class'))
    assert classes
    with zipfile.ZipFile(fault, 'a') as archive:
        for path in classes:
            archive.write(path, 'BOOT-INF/classes/studying/blog/fault/' + path.name)
    env = Environment(meta['project'], fault, folder)
    env.base += ['-f', str(Path(meta['folder']) / 'compose.yml')]
    env.url = meta['api']
    def sql(query):
        # Batch output otherwise escapes the JSON quotes inside the Outbox payload again.
        return env.dc('exec', '-T', 'mysql', 'mysql', '--raw', '--default-character-set=utf8mb4', '-uroot',
                      '-pperf-only-password', '-N', '-B', 'ticketflow_perf', data=query)[0].strip()
    def record(phase, **values):
        with (folder / 'timeline.ndjson').open('a') as file:
            file.write(json.dumps(dict(time_ms=time.time()*1000, phase=phase, **values), ensure_ascii=False) + '\n')
    def wait(predicate, label, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(.25)
        raise AssertionError('Timed out: ' + label)
    def old_state():
        return sql("SELECT JSON_OBJECT('payment',p.status,'paymentVersion',p.version,'booking',b.status,'bookingId',b.id) "
                   "FROM payment p JOIN booking b ON b.id=p.booking_id WHERE p.id=1")
    original = old_state()
    assert json.loads(original)['payment'] == 'COMPLETED'
    protected_max = int(sql('SELECT COALESCE(MAX(id),0) FROM payment'))
    def protected_state():
        return sql(f"SELECT p.id,p.status,p.version,b.id,b.status FROM payment p JOIN booking b ON b.id=p.booking_id WHERE p.id<={protected_max} ORDER BY p.id")
    protected_before = protected_state()
    (folder / 'protected-before.tsv').write_text(protected_before)
    cid = env.concert('TOSS_REAL_CANCEL_TEST', 3, 'flow')
    sql(f"UPDATE concert SET price=1,discount_rate=0,venue='Local Test Hall',artist='Toss cancellation test' WHERE id={cid}")
    overlay = folder / 'compose.probe.yml'
    overlay.write_text('''services:
  app:
    volumes:
      - ''' + str(gates) + ''':/fault-live
    environment:
      SPRING_PROFILES_ACTIVE: docker,toss-live-cancel
      FAULT_LIVE_CONCERT_ID: "''' + str(cid) + '''"
''')
    env.base += ['-f', str(overlay)]
    token = None
    current = dict(status='IN_PROGRESS', folder=str(folder), project=env.project, concert_id=cid,
                   url=meta['url'] + f'/concerts/{cid}', manual_worker_invocations=0,
                   cancellation_intent_is_fixture=True, original_payment_id=1)
    (folder / 'environment.json').write_text(json.dumps(current, indent=2))
    (ROOT / 'test-results/toss-live-cancel/latest.json').write_text(json.dumps(current, indent=2))
    shutil.copy2(Path(__file__), folder / 'runner.py')
    shutil.copy2(ROOT / 'src/test/java/studying/blog/fault/TossLiveCancelProbe.java', folder / 'TossLiveCancelProbe.java')
    (folder / 'jar-hashes.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [normal, fault]}, indent=2))
    (folder / 'command.txt').write_text('python3 perf/toss_live_cancel.py\n')
    def pg_lookup(key, label):
        assert re.fullmatch(r'[A-Za-z0-9_-]{1,200}', key)
        request = urllib.request.Request('https://api.tosspayments.com/v1/payments/' + key,
                  headers={'Authorization': 'Basic ' + base64.b64encode((secret + ':').encode()).decode()})
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
        selected = {k: result.get(k) for k in ['status','method','totalAmount','balanceAmount','easyPay','approvedAt']}
        selected['cancels'] = [{k: c.get(k) for k in ['cancelAmount','cancelReason','cancelStatus','canceledAt']}
                               for c in (result.get('cancels') or [])]
        (folder / (label + '.json')).write_text(json.dumps(selected, ensure_ascii=False, indent=2))
        return selected
    def snapshot(label):
        data = dict(
            payment=json.loads(sql(f"SELECT JSON_OBJECT('id',id,'status',status,'phase',recovery_phase,'bookingId',booking_id,'amount',amount,'method',payment_method,'retryCount',retry_count,'nextRetryAt',next_retry_at,'cancelReason',cancel_reason) FROM payment WHERE id={current['payment_id']}")),
            booking=json.loads(sql(f"SELECT JSON_OBJECT('id',id,'status',status) FROM booking WHERE id={current['booking_id']}")),
            attempt=json.loads(sql(f"SELECT JSON_OBJECT('id',id,'status',status,'bookingId',booking_id) FROM booking_attempt WHERE id='{current['attempt_id']}'")),
            outbox=json.loads(sql(f"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id',id,'status',status,'retryCount',retry_count,'payload',payload)),JSON_ARRAY()) FROM payment_compensation_outbox WHERE JSON_EXTRACT(payload,'$.paymentId')={current['payment_id']}")),
            remaining=int(env.redis('GET', f'seats:concert:{cid}')),
            active=env.redis('GET', f'booking:active:concert:{cid}:user:1'))
        fields = env.redis('HGETALL', 'booking:attempt:' + current['attempt_id']).splitlines()
        data['marker'] = dict(zip(fields[::2], fields[1::2]))
        (folder / (label + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2))
        record('snapshot', label=label, values=data)
        return data
    try:
        env.dc('up', '-d', '--force-recreate', 'app', logfile=folder / 'probe-start.log')
        def ready():
            try:
                with urllib.request.urlopen(env.url + '/api/test-support/csv', timeout=3) as response:
                    rows = list(csv.DictReader(io.StringIO(response.read().decode())))
                return rows[0]['token'] if len(rows) == 500 else None
            except Exception:
                return None
        token = wait(ready, 'app startup')
        record('ready_for_user', url=current['url'])
        (folder / 'ready.json').write_text(json.dumps({'url':current['url']}, indent=2))
        print('READY', current['url'], flush=True)
        approval_file = gates / 'approve-result.json'
        wait(approval_file.exists, 'user test approval', 1800)
        approval = json.loads(approval_file.read_text())
        order, key = approval['orderId'], approval['paymentKey']
        assert re.fullmatch('dev-order-TICKETFLOW-' + str(cid) + '-[a-zA-Z0-9]+', order)
        assert approval['status'] == 'DONE' and approval['totalAmount'] == 1
        pid, bid = map(int, sql(f"SELECT id,booking_id FROM payment WHERE idempotency_key='{order}'").split('\t'))
        aid = sql(f'SELECT id FROM booking_attempt WHERE booking_id={bid}')
        assert re.fullmatch(r'[0-9a-f-]{36}', aid)
        current.update(payment_id=pid, booking_id=bid, attempt_id=aid, order_id=order)
        before = snapshot('before-cancel-intent')
        assert before['payment']['status'] == 'PENDING' and before['booking']['status'] == 'PENDING_PAYMENT'
        assert before['remaining'] == 2 and not before['outbox']
        pg = pg_lookup(key, 'pg-approved')
        assert pg['status'] == 'DONE' and pg['totalAmount'] == pg['balanceAmount'] == 1
        # Only the cancellation INTENT fixture; no completed/canceled/returned result is written here.
        fixture = (f"START TRANSACTION; SELECT id FROM booking WHERE id={bid} FOR UPDATE; "
                   f"SELECT id FROM payment WHERE id={pid} FOR UPDATE; "
                   f"UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='TicketFlow booking cannot be finalized',version=version+1 "
                   f"WHERE id={pid} AND status='PENDING'; COMMIT;")
        sql(fixture)
        record('fixture_cancellation_intent', sql=fixture)
        snapshot('intent-committed')
        (gates / 'worker.resume').touch()
        wait((gates / 'cancel-result.json').exists, 'automatic Worker full cancellation')
        cancellation = json.loads((gates / 'cancel-result.json').read_text())
        assert cancellation['status'] == 'CANCELED' and cancellation['balanceAmount'] == 0
        pg = pg_lookup(key, 'pg-canceled-before-db-apply')
        assert pg['status'] == 'CANCELED' and pg['balanceAmount'] == 0
        assert len(pg['cancels']) == 1 and pg['cancels'][0]['cancelAmount'] == 1
        middle = snapshot('pg-canceled-before-db-apply')
        assert middle['remaining'] == 2 and not middle['outbox']
        assert middle['payment']['status'] == 'PENDING'
        (gates / 'cancel.resume').touch()
        wait(lambda: sql(f"SELECT status FROM booking WHERE id={bid}") == 'CANCELLED'
             and env.redis('GET', f'seats:concert:{cid}') == '3'
             and sql(f"SELECT status FROM booking_attempt WHERE id='{aid}'") == 'RELEASED'
             and sql(f"SELECT status FROM payment_compensation_outbox WHERE JSON_EXTRACT(payload,'$.paymentId')={pid}") == 'PUBLISHED',
             'automatic Outbox return')
        after = snapshot('returned-before-old-approval')
        assert after['payment']['status'] == 'CANCELED' and after['payment']['phase'] == 'NONE'
        assert after['marker'].get('releaseOutcome') == 'RETURNED' and not after['active']
        assert len(after['outbox']) == 1
        (gates / 'approval.resume').touch()
        # A PG-client return alone is insufficient: observe the original coordinator returning
        # AFTER its stale result handling and DB response transaction have completed.
        confirm_file = gates / 'original-confirm-result.json'
        wait(confirm_file.exists, 'original confirmation processing completion')
        original_response = json.loads(confirm_file.read_text())
        assert original_response['status'] == 'CANCELED'
        after_stale = snapshot('after-original-confirm-completed')
        assert after_stale == after, 'Stale approval changed committed cancellation or stock'
        payload = {'paymentKey':key, 'orderId':order, 'amount':1}
        for repeat in range(3):
            request = urllib.request.Request(env.url + f'/api/concerts/{cid}/payment/toss-confirm',
                data=json.dumps(payload).encode(), headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
            with urllib.request.urlopen(request, timeout=15) as response:
                data = json.load(response)
            (folder / f'duplicate-{repeat}.json').write_text(json.dumps(data, ensure_ascii=False, indent=2))
            assert data['status'] == 'CANCELED'
        def read_events():
            # Only an in-progress final append may be incomplete; complete malformed lines fail.
            return [json.loads(line) for line in (gates / 'events.ndjson').read_text().splitlines(keepends=True)
                    if line.endswith('\n')]
        def scans():
            return sum(e['phase'] == 'automatic_scan_completed' for e in read_events())
        def outbox_scans():
            return sum(e['phase'] == 'automatic_outbox_query_completed' for e in read_events())
        count = scans()
        outbox_count = outbox_scans()
        wait(lambda: scans() >= count + 2, 'two extra automatic scans', 30)
        wait(lambda: outbox_scans() >= outbox_count + 2, 'two extra actual Outbox queries', 30)
        final = snapshot('final')
        assert final['payment']['status'] == 'CANCELED' and final['booking']['status'] == 'CANCELLED'
        assert final['attempt']['status'] == 'RELEASED' and final['remaining'] == 3
        assert len(final['outbox']) == 1 and final['outbox'][0]['status'] == 'PUBLISHED'
        assert final == after_stale, 'Extra scheduler runs or duplicates changed terminal results'
        events = read_events()
        assert any(e['phase'] == 'original_confirm_completed' for e in events)
        assert all(e['thread'].startswith('scheduling-') for e in events
                   if e['phase'] in {'automatic_scan_completed', 'automatic_outbox_query_completed'})
        assert sum(e['phase'] == 'release_result' and e['value'] == 'RETURNED' for e in events) == 1
        assert sum(e['phase'] == 'approve_http_start' for e in events) == 1
        assert sum(e['phase'] == 'cancel_http_start' for e in events) == 1
        pg = pg_lookup(key, 'pg-final')
        assert pg['status'] == 'CANCELED' and pg['balanceAmount'] == 0 and len(pg['cancels']) == 1
        assert old_state() == original, 'Original confirmed payment changed'
        assert protected_state() == protected_before, 'Earlier confirmed/canceled payments changed'
        (folder / 'protected-after.tsv').write_text(protected_state())
        current.update(status='SUCCESS', extra_scans=scans()-count, approve_http_calls=1,
                       extra_outbox_queries=outbox_scans()-outbox_count,
                       original_confirm_completed=True, original_confirm_status=original_response['status'],
                       cancel_http_calls=1, redis_returned_effects=1, original_preserved=True)
        print('SUCCESS real Toss full cancellation and exactly one ticket return', flush=True)
    except BaseException as failure:
        current.update(status='FAILURE', error=str(failure), traceback=traceback.format_exc())
        record('failure', error=str(failure))
        if 'payment_id' in current:
            try:
                snapshot('failure')
            except Exception as error:
                record('snapshot_failed', error=str(error))
        raise
    finally:
        for name in ['approval.resume', 'worker.resume', 'cancel.resume']:
            (gates / name).touch()
        if (gates / 'approve-result.json').exists():
            # Even failure cleanup lets the released original request finish before restart.
            deadline = time.monotonic() + 5
            while not (gates / 'original-confirm-result.json').exists() and time.monotonic() < deadline:
                time.sleep(.05)
        env.dc('logs', '--no-color', 'app', logfile=folder / 'probe-app.log', check=False)
        # Restore the same normal application; no test component remains in the UI environment.
        env.base = env.base[:-2]
        env.env['PERF_APP_JAR'] = str(normal)
        try:
            env.dc('up', '-d', '--force-recreate', 'app', logfile=folder / 'restore-normal.log')
        except Exception as error:
            current['restore_error'] = str(error)
        after = {str(f.relative_to(ROOT)): hashlib.sha256(f.read_bytes()).hexdigest()
                 for f in (ROOT / 'src/main').rglob('*') if f.is_file()}
        current['production_source_unchanged'] = sources == after
        (folder / 'summary.json').write_text(json.dumps(current, ensure_ascii=False, indent=2))
        (ROOT / 'test-results/toss-live-cancel/latest.json').write_text(json.dumps(current, ensure_ascii=False, indent=2))
        assert sources == after


if __name__ == '__main__':
    main()
