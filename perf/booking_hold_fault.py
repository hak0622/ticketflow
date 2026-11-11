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


from local_three_fault import Suite as OriginalSuite

class Suite(OriginalSuite):
    def crash_after_reserve(self):
        cid, aid = self.fixture('hold_crash_recovery')
        self.arm(aid, 'after_reserve')
        future = self.launch('app', cid, aid)
        self.paused(aid, 'after_reserve')
        before = self.snapshot(cid, aid, 'before_kill')
        self.check(before['remaining'] == 9 and before['bookings'] == 0, 'Held but not booked')
        self.kill('app')
        self.check(future.result(20)['status'] == 0, 'SIGKILL interrupts HTTP')
        final = self.terminal(cid, aid, 'BOOKED')
        self.automatic_worker(aid, excluded=('app',))
        self.check(final['marker']['holdExpiresAt'] == before['marker']['holdExpiresAt'], 'Lease never extended')
        for service in ['app-2','app-3']:
            response = self.request(service,cid,aid)
            self.check(response['status'] == 200, 'Replay uses existing Booking')
        self.terminal(cid, aid, 'BOOKED')

    def run(self):
        for test in [self.crash_after_reserve,self.crash_after_commit,self.duplicate_three_apps,self.late_original_after_worker]:
            self.current={'name':test.__name__,'started_at_ms':time.time()*1000}
            print('START',test.__name__,flush=True)
            try:
                test(); self.current['status']='PASS'
            except Exception:
                self.current.update(status='FAIL',error=traceback.format_exc()); print(self.current['error'],flush=True)
            finally:
                for path in self.gates.glob('*.arm'):path.with_suffix('.resume').touch()
                self.results.append(self.current)
                (self.folder/'results.json').write_text(json.dumps(self.results,indent=2))
                print(self.current['status'],test.__name__,flush=True)
        return all(r['status']=='PASS' for r in self.results)

def main():
    run_id = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = ROOT / 'test-results/booking-hold-recovery/process' / run_id
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
    for name in ['booking_hold_fault.py', 'local_three_fault.py', 'local_three.py', 'run.py', 'compose.yml', 'compose.local-three.yml',
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
