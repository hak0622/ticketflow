#!/usr/bin/env python3
"""Verify round-deduplicated retries using real automatic Workers; no manual recovery."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import time
import uuid
import zipfile

from local_three import ThreeApps
from payment_saga_fault import Suite
from run import ROOT, build_jar, command


class RetrySuite(Suite):
    def state(self, label):
        value = self.snapshot(label)
        value['payment'].update(self.json_sql(f"SELECT JSON_OBJECT('last_error',last_error,'version',version) FROM payment WHERE id={self.current['payment_id']}"))
        self.current.setdefault('observations', []).append({
            'label': label, 'at_ms': time.time()*1000, 'payment': value['payment']})
        return value['payment']

    def count_ends(self, app):
        return len(self.events(self.current['pg_key'], 'worker_end', app))

    def await_end(self, app, previous):
        self.wait(lambda: self.count_ends(app) > previous, 'automatic Worker finished '+app)

    def healthy_terminal(self, future):
        self.recovery_deadline = getattr(self, 'healthy_only_since', time.monotonic())+100
        self.terminal(True)
        key = self.current['pg_key']
        self.resume(key, 'after_approve', 'app')
        response = future.result(30)
        self.check(response['status'] == 200 and json.loads(response['body'])['status'] == 'COMPLETED',
                   'held original approval reuses COMPLETED')

    def finish(self, payload):
        # Restore PG accessibility before duplicate requests / two-scan observation.
        self.control(self.current['pg_key'], ['app', 'app-2', 'app-3'])
        self.replays(payload, True)

    def selected_wave(self):
        payload, future = self.fixture('retry_same_selection')
        key = self.current['pg_key']
        self.control(key, ['app'])
        for app in ['app', 'app-2', 'app-3']:
            self.arm(key, 'worker_start', app)
        self.scans(['app', 'app-2', 'app-3'])
        for app in ['app', 'app-2', 'app-3']:
            self.paused(key, 'worker_start', app)
        before = self.state('all_three_selected_same_due_payment')
        first_failure = None
        for app in ['app-2', 'app-3']:
            previous = self.count_ends(app)
            self.resume(key, 'worker_start', app)
            self.await_end(app, previous)
            state = self.state('failure_written_'+app)
            self.check(state['retry_count'] == before['retry_count']+1,
                       'overlapping failures increment shared retry count only once')
            if first_failure is not None:
                self.check(state == first_failure, 'duplicate HTTP failure changes no Payment fields')
            first_failure = state
        self.resume(key, 'worker_start', 'app')
        self.healthy_terminal(future)
        self.state('healthy_already_selected_worker_ignores_new_future_deadline')
        self.finish(payload)

    def deferred_scans(self, label):
        pid = str(self.current['payment_id'])
        start = time.time()*1000
        self.healthy_only_since = time.monotonic()
        self.scans(['app'])
        def scans():
            return [e for e in self.events('global', 'selected', 'app')
                    if float(e['time_ms']) >= start]
        self.wait(lambda: len(scans()) >= 2, 'two healthy automatic candidate scans')
        self.check(all(pid not in e['detail'].split(',') for e in scans()),
                   'healthy scans cannot select payment postponed by failed Workers')
        self.check(not self.events(self.current['pg_key'], 'worker_start', 'app'),
                   'healthy Worker has not started recovery despite running scans')
        self.state(label)
        self.record('healthy_skipped_scans', scans=scans())

    def repeated_deferral(self, rounds=2):
        payload, future = self.fixture('retry_repeated_deferral')
        key = self.current['pg_key']
        self.control(key, ['app'])
        for round_number in range(1, rounds+1):
            for app in ['app-2', 'app-3']:
                # Reset only the test gate; never modify Payment or its retry deadline.
                prefix = self.gates/f'{key}-worker_start-{app}'
                for suffix in ['.claimed', '.resume']:
                    (prefix.with_name(prefix.name+suffix)).unlink(missing_ok=True)
                self.arm(key, 'worker_start', app)
            self.scans(['app-2', 'app-3'])
            for app in ['app-2', 'app-3']:
                # Read new gate claim rather than historical event from the preceding round.
                self.wait(lambda app=app: (self.gates/f'{key}-worker_start-{app}.claimed').exists(),
                          'due selection round '+str(round_number)+' '+app)
            first_failure = None
            for app in ['app-2', 'app-3']:
                previous = self.count_ends(app)
                self.resume(key, 'worker_start', app)
                self.await_end(app, previous)
                state = self.state('round_'+str(round_number)+'_failure_'+app)
                self.check(state['retry_count'] == 1+round_number, 'exactly one retry increment in this selected round')
                if first_failure is not None:
                    self.check(state == first_failure, 'second failure preserves round deadline and error')
                first_failure = state
            self.deferred_scans('round_'+str(round_number)+'_healthy_skipped')
        # Failed replicas remain paused at scan entry, not inside production locks.
        # A now recovers at the persisted deadline with its unchanged 5-second schedule.
        self.healthy_terminal(future)
        self.current['failure_rounds'] = rounds
        self.finish(payload)

    def late_failure(self):
        payload, future = self.fixture('retry_late_failure')
        key = self.current['pg_key']
        self.control(key, ['app'])
        self.arm(key, 'failed_lookup', 'app-2')
        self.scans(['app-2'])
        self.paused(key, 'failed_lookup', 'app-2')
        # Error is a real HTTP 503, held outside HTTP and before recordRetry.
        self.scans(['app'])
        self.healthy_terminal(future)
        before = self.state('success_committed_before_stale_failure')
        previous = self.count_ends('app-2')
        self.resume(key, 'failed_lookup', 'app-2')
        self.await_end('app-2', previous)
        after = self.state('stale_failure_finished')
        self.check(before == after, 'late failure cannot alter terminal Payment or retry deadline')
        self.finish(payload)

    def natural_asymmetry(self):
        payload, future = self.fixture('retry_natural_asymmetry')
        key = self.current['pg_key']
        self.control(key, ['app'])
        start = time.time()*1000
        self.scans(['app', 'app-2', 'app-3'])
        self.healthy_terminal(future)
        self.current['recovery_elapsed_ms'] = time.time()*1000-start
        self.state('ungated_asymmetric_recovery')
        self.finish(payload)

    def run_retry(self, repeats):
        methods = [('selected_wave', self.selected_wave),
                   ('repeated_deferral', self.repeated_deferral),
                   ('late_failure', self.late_failure),
                   ('natural_asymmetry', self.natural_asymmetry),
                   ('three_round_deferral', lambda: self.repeated_deferral(3))]
        import traceback
        for name, test in methods:
            for trial in range(1, repeats+1):
                self.current = dict(case=name, trial=trial, status='RUNNING', started_at_ms=time.time()*1000)
                if hasattr(self, 'healthy_only_since'):
                    del self.healthy_only_since
                print('START', name, trial, flush=True)
                try:
                    test()
                    self.current['status'] = 'SUCCESS'
                except Exception as failure:
                    self.current.update(status='FAILURE', error=str(failure), traceback=traceback.format_exc())
                    try: self.snapshot('failure_evidence')
                    except Exception as evidence: self.current['snapshot_error'] = str(evidence)
                    print('FAILURE', name, trial, failure, flush=True)
                finally:
                    for arm in self.gates.glob(self.current.get('pg_key', '')+'-*.arm'):
                        arm.with_suffix('.resume').touch()
                    self.scans(['app', 'app-2', 'app-3'])
                    self.current['finished_at_ms'] = time.time()*1000
                    self.results.append(self.current)
                    (self.folder/'results.json').write_text(json.dumps(self.results, indent=2))
                    print(self.current['status'], name, trial, flush=True)
        return all(x['status']=='SUCCESS' for x in self.results)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repeats', type=int, choices=[1, 2, 3], default=3)
    args = parser.parse_args()
    folder = ROOT/'test-results/payment-retry-fault'/(time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
    folder.mkdir(parents=True)
    for name in ['gates', 'pg']: (folder/name).mkdir()
    print('Results:', folder, flush=True)
    (folder/'command.txt').write_text('PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 '+' '.join(sys.argv)+'\n')
    source = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in (ROOT/'src/main').rglob('*') if p.is_file()}
    baseline_path = ROOT/'test-results/payment-retry-fault/20261110-105436-721815/environment.json'
    baseline = json.loads(baseline_path.read_text())['source_sha256']
    changed = sorted(name for name in set(source)|set(baseline) if source.get(name)!=baseline.get(name))
    (folder/'environment.json').write_text(json.dumps(dict(source_sha256=source, repeats=args.repeats,
        baseline=str(baseline_path), changed_production_files=changed, retry_round_deduplicated=True), indent=2))
    (folder/'source').mkdir()
    for name in changed:
        shutil.copy2(ROOT/name, folder/'source'/Path(name).name)
    command(['git', 'status', '--short'], logfile=folder/'repository-state.txt')
    production = build_jar(ROOT, folder/'build.log')
    command(['./gradlew', 'testClasses', '--offline'], logfile=folder/'test-classes.log')
    jar = folder/'fault-app.jar'
    shutil.copy2(production, jar)
    with zipfile.ZipFile(jar, 'a') as archive:
        for p in (ROOT/'build/classes/java/test/studying/blog/fault').glob('PaymentSagaFaultProbe*.class'):
            archive.write(p, 'BOOT-INF/classes/studying/blog/fault/'+p.name)
    with zipfile.ZipFile(production) as archive:
        assert not any('PaymentSagaFaultProbe' in name for name in archive.namelist())
    (folder/'jar-hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [production,jar]},indent=2))
    for name in ['payment_retry_fault.py', 'payment_saga_fault.py', 'payment_pg.py',
                 'compose.payment-saga-fault.yml', 'compose.local-three.yml', 'compose.yml', 'run.py', 'local_three.py']:
        shutil.copy2(ROOT/'perf'/name, folder/name)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java', folder)
    env = ThreeApps('tfperf-pg-retry-'+folder.name, jar, folder, users=100)
    env.base += ['-f', str(ROOT/'perf/compose.payment-saga-fault.yml')]
    env.env.update(FAULT_DIR=str(folder/'gates'), PG_DATA_DIR=str(folder/'pg'))
    suite = None
    try:
        env.dc('up', '-d', 'pg', logfile=folder/'pg-start.log')
        env.start()
        suite = RetrySuite(folder, env)
        if not suite.run_retry(args.repeats): raise SystemExit(1)
    finally:
        if suite:
            for arm in suite.gates.glob('*.arm'): arm.with_suffix('.resume').touch()
            for block in suite.gates.glob('scan-*.block'): block.unlink(missing_ok=True)
            suite.pool.shutdown(wait=True)
            try: (folder/'pg-final.json').write_text(json.dumps(suite.pg(), indent=2))
            except Exception: pass
        env.dc('logs', '--no-color', 'pg', logfile=folder/'pg.log', check=False)
        env.close()
        after = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in (ROOT/'src/main').rglob('*') if p.is_file()}
        results = suite.results if suite else []
        (folder/'summary.json').write_text(json.dumps(dict(results=results,
            success=sum(r['status']=='SUCCESS' for r in results),
            failure=sum(r['status']=='FAILURE' for r in results),
            production_source_unchanged=source==after, manual_worker_invocations=0), indent=2))
        assert source==after


if __name__ == '__main__': main()
