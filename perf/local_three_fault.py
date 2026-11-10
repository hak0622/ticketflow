#!/usr/bin/env python3
"""Real SIGKILL + automatic scheduled recovery on three disposable Spring JVMs.

No production source, grace period, scheduler interval or pool settings are changed.
The runner never invokes recover()/the Worker or updates attempt deadlines itself.
"""
import concurrent.futures
import csv
import hashlib
import json
import shutil
import time
import traceback
import urllib.error
import urllib.request
import uuid
import zipfile

from local_three import ThreeApps
from run import ROOT, build_jar, command, http


class Suite:
    def __init__(self, folder, env):
        self.folder, self.env = folder, env
        self.gates = folder / 'gates'
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=12)
        self.token = next(csv.DictReader((folder / 'users.csv').open()))['token']
        self.results = []
        self.current = None

    def record(self, kind, **values):
        event = {'time_ms': time.time() * 1000, 'kind': kind, **values}
        with (self.folder / 'timeline.ndjson').open('a') as file:
            file.write(json.dumps(event) + '\n')
        return event

    def check(self, condition, description):
        self.record('assertion', passed=bool(condition), description=description)
        if not condition:
            raise AssertionError(description)

    def wait(self, predicate, description, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = predicate()
            if value:
                return value
            time.sleep(.25)
        raise AssertionError('Timed out: ' + description)

    def events(self, aid, phase=None):
        rows = []
        for path in self.gates.glob('events-*.tsv'):
            for line in path.read_text().splitlines():
                fields = line.split('\t')
                if len(fields) == 6 and fields[3] == aid and (phase is None or fields[2] == phase):
                    rows.append(dict(zip(['time_ms', 'instance', 'phase', 'id', 'thread', 'detail'], fields)))
        return sorted(rows, key=lambda row: int(row['time_ms']))

    def arm(self, aid, phase, target='app'):
        (self.gates / (aid + '-' + phase + '.arm')).write_text(target)

    def paused(self, aid, phase):
        rows = self.wait(lambda: self.events(aid, 'paused_' + phase), 'gate ' + phase)
        self.record('gate', event=rows[-1])
        return rows[-1]['instance']

    def resume(self, aid, phase):
        (self.gates / (aid + '-' + phase + '.resume')).touch()

    def url(self, service):
        port = self.env.dc('port', service, '8080')[0].strip().rsplit(':', 1)[1]
        return 'http://127.0.0.1:' + port

    def request(self, service, cid, aid):
        start = time.time() * 1000
        req = urllib.request.Request(self.url(service) + f'/api/concerts/{cid}/booking', data=b'',
            headers={'Authorization': 'Bearer ' + self.token, 'Idempotency-Key': aid}, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=170) as response:
                status, body = response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read().decode()
        except Exception as error:
            status, body = 0, str(error)
        result = dict(instance=service, request_id=aid, status=status, body=body,
                      started_at_ms=start, finished_at_ms=time.time() * 1000)
        self.record('http', **result)
        return result

    def launch(self, service, cid, aid):
        return self.executor.submit(self.request, service, cid, aid)

    def kill(self, service):
        self.record('kill_start', instance=service)
        self.env.dc('kill', '-s', 'SIGKILL', service)
        container = self.env.dc('ps', '-a', '-q', service)[0].strip()
        state = json.loads(command(['docker', 'inspect', '--format', '{{json .State}}', container])[0])
        self.record('kill_complete', instance=service, state=state)
        self.check(state['ExitCode'] == 137 and not state['OOMKilled'], 'Actual SIGKILL exit 137, no OOM: ' + service)

    def ensure_apps(self):
        self.env.dc('up', '-d', 'app', 'app-2', 'app-3')
        for service in ['app', 'app-2', 'app-3']:
            port = self.env.dc('port', service, '8081')[0].strip().rsplit(':', 1)[1]
            def ready():
                try:
                    return json.loads(http('http://127.0.0.1:' + port + '/actuator/health'))['status'] == 'UP'
                except Exception:
                    return False
            self.wait(ready, service + ' restart readiness', 180)

    def fixture(self, name):
        self.ensure_apps()
        cid = self.env.concert(name, 10, 'api')
        aid = str(uuid.uuid4())
        self.current.update(concert_id=cid, request_id=aid)
        self.record('fixture', concert_id=cid, request_id=aid, capacity=10)
        return cid, aid

    def snapshot(self, cid, aid, label):
        row = self.env.sql(f"SELECT status,result_status,COALESCE(booking_id,0),retry_count FROM booking_attempt WHERE id='{aid}'")
        attempt = dict(zip(['status', 'result', 'booking_id', 'retry_count'], row.split('\t'))) if row else None
        counts = self.env.sql(f"SELECT COUNT(*),COALESCE(SUM(status<>'CANCELLED'),0),COUNT(DISTINCT user_id) FROM booking WHERE concert_id={cid}")
        bookings, active, unique = map(int, counts.split('\t'))
        marker = self.env.redis('HGETALL', 'booking:attempt:' + aid).splitlines()
        result = dict(attempt=attempt, remaining=int(self.env.redis('GET', f'seats:concert:{cid}')),
            bookings=bookings, active_bookings=active, unique_users=unique,
            marker=dict(zip(marker[::2], marker[1::2])),
            admission_exists=int(self.env.redis('EXISTS', f'admitted:concert:{cid}:user:1')),
            admission_expires=self.env.redis('PEXPIRETIME', f'admitted:concert:{cid}:user:1'),
            active_request=self.env.redis('GET', f'booking:active:concert:{cid}:user:1'),
            attempt_count=int(self.env.sql(f'SELECT COUNT(*) FROM booking_attempt WHERE concert_id={cid}')))
        self.record('snapshot', label=label, concert_id=cid, request_id=aid, values=result)
        return result

    def wait_status(self, aid, status):
        self.wait(lambda: self.env.sql(f"SELECT status FROM booking_attempt WHERE id='{aid}'") == status,
                  'automatic DB terminal state ' + status)

    def terminal(self, cid, aid, status):
        self.wait_status(aid, status)
        s = self.snapshot(cid, aid, 'terminal')
        booked = status == 'BOOKED'
        self.check(s['remaining'] == (9 if booked else 10), 'Exact Redis remaining')
        self.check(s['bookings'] == s['active_bookings'] == s['unique_users'] == int(booked), 'Exact Booking rows / unique users')
        self.check(s['attempt_count'] == 1 and s['attempt']['status'] == status, 'One terminal attempt')
        self.check(s['remaining'] + s['active_bookings'] == 10, 'Quantity conservation')
        self.check(s['marker'].get('state') == ('HELD' if booked else 'RELEASED'), 'Redis marker matches DB')
        self.check(s['active_request'] == (aid if booked else ''), 'Active request matches state')
        if booked:
            linked = self.env.sql(f"SELECT COUNT(*) FROM booking b JOIN booking_attempt a ON a.booking_id=b.id WHERE a.id='{aid}' AND b.concert_id={cid} AND b.user_id=1 AND b.status='PENDING_PAYMENT'")
            self.check(linked == '1', 'BOOKED linked to actual committed Booking')
        else:
            self.check(s['admission_exists'] == 1, 'Original unexpired admission available')
        return s

    def automatic_worker(self, aid, excluded=()):
        self.wait(lambda: self.events(aid, 'worker_end'), 'automatic Worker completion')
        events = self.events(aid, 'worker_end')
        self.check(any(e['instance'] not in excluded and 'scheduling' in e['thread'] for e in events),
                   'Surviving app scheduled Worker executed recovery')
        self.current['worker_events'] = events

    def replay_failed(self, cid, aid, service='app-2'):
        before = self.snapshot(cid, aid, 'before_replay')
        responses = [self.request(service, cid, aid) for _ in range(3)]
        self.check(all(r['status'] == 409 and json.loads(r['body']).get('status') == 'BOOKING_FAILED' for r in responses),
                   'Same request returns existing failure')
        after = self.snapshot(cid, aid, 'after_replay')
        self.check(before == after, 'Repeated terminal request makes no DB/Redis state changes')

    def crash_after_reserve(self):
        cid, aid = self.fixture('crash_after_reserve')
        self.arm(aid, 'after_reserve')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'after_reserve')
        before = self.snapshot(cid, aid, 'real_hold_before_kill')
        self.check(before['remaining'] == 9 and before['bookings'] == 0 and before['attempt']['status'] == 'REQUESTED',
                   'Real Lua hold, REQUESTED committed, Booking absent')
        self.kill('app')
        self.check(future.result(20)['status'] == 0, 'Killed process did not return an HTTP success')
        final = self.terminal(cid, aid, 'RELEASED')
        self.automatic_worker(aid, excluded=('app',))
        self.check(final['marker'].get('releaseOutcome') == 'RETURNED', 'Actual held ticket returned')
        self.check(abs(int(final['admission_expires']) - int(before['marker']['admissionExpires'])) <= 5,
                   'Admission restored with original absolute expiry, no TTL extension')
        self.replay_failed(cid, aid)

    def crash_after_commit(self):
        cid, aid = self.fixture('crash_after_commit')
        self.arm(aid, 'after_commit')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'after_commit')
        self.terminal(cid, aid, 'BOOKED')
        self.kill('app')
        self.check(future.result(20)['status'] == 0, 'Committed request response lost through actual process kill')
        # Pass both the 30s REQUESTED grace and a scheduler cycle; BOOKED must never be returned.
        self.wait(lambda: time.time() * 1000 > future.result()['started_at_ms'] + 36_000,
                  'BOOKED survives recovery eligibility window', 45)
        responses = [self.request(service, cid, aid) for service in ['app-2', 'app-3', 'app-2']]
        self.check(all(r['status'] == 200 and json.loads(r['body']).get('status') == 'BOOKED' for r in responses),
                   'Other apps replay committed success')
        self.terminal(cid, aid, 'BOOKED')
        self.check(not self.events(aid, 'after_release'), 'No return Lua for committed success')

    def duplicate_three_apps(self):
        cid, aid = self.fixture('duplicate_three_apps')
        futures = [self.launch(service, cid, aid) for service in ['app', 'app-2', 'app-3'] * 3]
        replies = [future.result(60) for future in futures]
        self.check(all(r['status'] == 200 and json.loads(r['body']).get('status') == 'BOOKED' for r in replies),
                   'Nine concurrent calls across three apps reuse one success')
        self.terminal(cid, aid, 'BOOKED')
        self.check(len(self.events(aid, 'after_reserve')) == 1, 'Only one reserve Lua service call')

    def original_worker_lock_race(self):
        cid, aid = self.fixture('original_worker_lock_race')
        self.arm(aid, 'after_reserve')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'after_reserve')
        self.wait(lambda: self.events(aid, 'worker_start'), 'natural 30s candidate grace')
        wait_sql = """SELECT COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            WHERE l.OBJECT_SCHEMA='ticketflow_perf' AND l.OBJECT_NAME='booking_attempt'"""
        waits = self.wait(lambda: int(self.env.sql(wait_sql)), 'actual MySQL attempt row lock contention', 15)
        self.record('mysql_lock_wait_observed', waiting_edges=waits)
        self.resume(aid, 'after_reserve')
        response = future.result(40)
        self.check(response['status'] != 200, 'Overdue original transaction cannot commit stale success')
        self.terminal(cid, aid, 'RELEASED')
        self.automatic_worker(aid)
        self.replay_failed(cid, aid)

    def crash_during_worker_return(self):
        cid, aid = self.fixture('crash_during_worker_return')
        self.arm(aid, 'after_reserve')
        self.arm(aid, 'after_release', 'any')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'after_reserve')
        self.kill('app')
        future.result(20)
        returning_app = self.paused(aid, 'after_release')
        before = self.snapshot(cid, aid, 'redis_returned_before_db_commit')
        self.check(returning_app != 'app', 'Surviving automatic Worker reached actual return')
        self.check(before['remaining'] == 10 and before['marker'].get('releaseOutcome') == 'RETURNED'
                   and before['attempt']['status'] == 'RELEASE_PENDING', 'Redis return committed, DB return commit not yet performed')
        self.kill(returning_app)
        survivor = next(s for s in ['app-2', 'app-3'] if s != returning_app)
        self.terminal(cid, aid, 'RELEASED')
        self.automatic_worker(aid, excluded=('app', returning_app))
        self.check(any(e['instance'] == survivor for e in self.events(aid, 'after_release')),
                   'Surviving Worker retried common return Lua')
        self.replay_failed(cid, aid, survivor)

    def late_original_after_worker(self):
        cid, aid = self.fixture('late_original_after_worker')
        self.arm(aid, 'before_execute')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'before_execute')
        self.terminal(cid, aid, 'RELEASED')
        self.automatic_worker(aid)
        before = self.snapshot(cid, aid, 'worker_finished_before_original')
        self.check(before['marker'].get('releaseOutcome') == 'NOT_HELD', 'Never-held request has terminal tombstone')
        self.resume(aid, 'before_execute')
        response = future.result(30)
        self.check(response['status'] == 409 and json.loads(response['body']).get('status') == 'BOOKING_FAILED',
                   'Late original receives existing terminal failure')
        self.check(not self.events(aid, 'after_reserve'), 'Late original never invokes reserve Lua')
        self.check(before == self.snapshot(cid, aid, 'after_late_original'), 'Late execution leaves all quantities and admission unchanged')

    def run(self):
        for test in [self.crash_after_reserve, self.crash_after_commit, self.duplicate_three_apps,
                     self.original_worker_lock_race, self.crash_during_worker_return, self.late_original_after_worker]:
            self.current = {'name': test.__name__, 'started_at_ms': time.time() * 1000}
            print('START', test.__name__, flush=True)
            try:
                test()
                self.current['status'] = 'PASS'
            except Exception:
                self.current.update(status='FAIL', error=traceback.format_exc())
                print(self.current['error'], flush=True)
            finally:
                # Release test gates even after failed assertions; no manual recovery calls.
                for path in self.gates.glob('*.arm'):
                    path.with_suffix('.resume').touch()
                self.current['finished_at_ms'] = time.time() * 1000
                self.results.append(self.current)
                (self.folder / 'results.json').write_text(json.dumps(self.results, indent=2))
                print(self.current['status'], test.__name__, flush=True)
        return all(row['status'] == 'PASS' for row in self.results)


def main():
    run_id = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = ROOT / 'build/fault-local-three' / run_id
    folder.mkdir(parents=True)
    (folder / 'gates').mkdir()
    print('Results:', folder, flush=True)
    production = build_jar(ROOT, folder / 'build.log')
    command(['./gradlew', 'testClasses', '--offline'], logfile=folder / 'test-classes.log')
    saved = folder / 'fault-app.jar'
    shutil.copy2(production, saved)
    with zipfile.ZipFile(saved, 'a') as archive:
        for path in (ROOT / 'build/classes/java/test/studying/blog/fault').glob('LocalThreeFaultProbe*.class'):
            archive.write(path, 'BOOT-INF/classes/studying/blog/fault/' + path.name)
    with zipfile.ZipFile(production) as archive:
        assert not any('LocalThreeFaultProbe' in name for name in archive.namelist())
    metadata = dict(production_jar_sha256=hashlib.sha256(production.read_bytes()).hexdigest(),
                    fault_jar_sha256=hashlib.sha256(saved.read_bytes()).hexdigest(),
                    source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in (ROOT / 'src/main').rglob('*') if p.is_file()},
                    scheduler_interval_seconds=5, requested_grace_seconds=30,
                    manual_worker_invocations=0, production_jar_has_fault_probe=False)
    (folder / 'environment.json').write_text(json.dumps(metadata, indent=2))
    for name in ['local_three_fault.py', 'local_three.py', 'run.py', 'compose.yml', 'compose.local-three.yml',
                 'compose.local-three-fault.yml', 'nginx.local-three.conf']:
        shutil.copy2(ROOT / 'perf' / name, folder / name)
    shutil.copy2(ROOT / 'src/test/java/studying/blog/fault/LocalThreeFaultProbe.java', folder)
    env = ThreeApps('tfperf-fault-three-' + run_id, saved, folder, users=100)
    env.base += ['-f', str(ROOT / 'perf/compose.local-three-fault.yml')]
    env.env['FAULT_DIR'] = str(folder / 'gates')
    suite = None
    try:
        env.start()
        suite = Suite(folder, env)
        passed = suite.run()
        print('ALL PASSED:', passed, flush=True)
        if not passed:
            raise SystemExit(1)
    finally:
        if suite:
            suite.executor.shutdown(wait=True)
        env.close()


if __name__ == '__main__':
    main()
