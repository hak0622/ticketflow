#!/usr/bin/env python3
from payment_commit_fault import *
class CancelSuite(CommitSuite):
    def commit_loss(self, before):
        payload,future=self.fixture('cancel_commit_ack')
        c=self.current;key=c['pg_key']
        self.cancel_fixture();self.arm(key,'before_cancel','app-2')
        (self.gates/'commit-target-app-2.arm').write_text(f"{c['payment_id']},{c['booking_id']},{key},CANCEL")
        self.control(key,['app-2']);self.scans(['app-2'])
        self.paused(key,'before_cancel','app-2')
        snap=self.snapshot('before_pg_cancel')
        self.check(snap['remaining']==2 and not snap['outboxes'] and snap['payment']['status']=='PENDING','no return before confirmed PG cancellation')
        self.resume(key,'before_cancel','app-2')
        self.wait(lambda:self.commit_events(key,'real_commit_returned'),'real cancellation commit')
        snap=self.snapshot('canceled_and_outbox_committed_without_ack')
        self.check(snap['payment']['status']=='CANCELED' and snap['payment']['phase']=='NONE' and len(snap['outboxes'])==1 and snap['outboxes'][0]['status']=='PENDING' and snap['remaining']==2,'atomic cancellation plus Outbox visible; ticket still held')
        saved=self.json_sql(f"SELECT JSON_OBJECT('version',version,'error',last_error,'count',retry_count,'next',next_retry_at,'status',status) FROM payment WHERE id={c['payment_id']}")
        self.recovery_deadline=time.monotonic()+180
        if before:
            self.scans(['app-3'])
            self.terminal(False)
            self.check(not self.commit_events(key,'jdbc_exception'),'return completed before JDBC exception')
        (self.gates/(key+'-commit.resume')).touch()
        self.wait(lambda:self.commit_events(key,'spring_commit_failure'),'commit failure propagated')
        if not before:self.scans(['app-2','app-3'])
        self.terminal(False)
        after=self.json_sql(f"SELECT JSON_OBJECT('version',version,'error',last_error,'count',retry_count,'next',next_retry_at,'status',status) FROM payment WHERE id={c['payment_id']}")
        self.check(saved==after,'late exception preserves cancellation and retry fields')
        self.resume(key,'after_approve','app')
        reply=future.result(30);self.check(reply['status']==200 and json.loads(reply['body'])['status']=='CANCELED','old approval cannot reverse cancellation')
        self.replays(payload,False)
        since=time.time()*1000
        self.wait(lambda:len([e for e in self.events('global','outbox_scan') if float(e['time_ms'])>=since])>=2,'two additional actual Outbox scans',40)
        self.validate(False,'after_additional_outbox_scans')
        (self.gates/'commit-target-app-2.arm').unlink(missing_ok=True)
    def run_commit(self,repeats):
        import traceback
        for name,worker in [('exception_first',False),('return_first',True)]:
            for trial in range(1,repeats+1):
                self.current=dict(case=name,trial=trial,status='RUNNING',started_at_ms=time.time()*1000)
                print('START',name,trial,flush=True)
                try:self.commit_loss(worker);self.current['status']='SUCCESS'
                except Exception as error:
                    self.current.update(status='FAILURE',error=str(error),traceback=traceback.format_exc())
                    try:self.snapshot('failure_evidence')
                    except Exception:pass
                    print('FAILURE',error,flush=True)
                finally:
                    key=self.current.get('pg_key','')
                    (self.gates/(key+'-commit.resume')).touch()
                    for arm in self.gates.glob(key+'-*.arm'):arm.with_suffix('.resume').touch()
                    self.scans(['app','app-2','app-3'])
                    self.current['finished_at_ms']=time.time()*1000
                    self.results.append(self.current)
                    (self.folder/'results.json').write_text(json.dumps(self.results,indent=2))
                    print(self.current['status'],name,trial,flush=True)
        return all(x['status']=='SUCCESS' for x in self.results)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repeats', type=int, choices=[1, 2, 3], default=3)
    args = parser.parse_args()
    folder = ROOT/'test-results/payment-cancel-commit-fault'/(time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
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
        for p in (ROOT/'build/classes/java/test/studying/blog/fault').glob('Payment*FaultProbe*.class'):
            archive.write(p, 'BOOT-INF/classes/studying/blog/fault/'+p.name)
    with zipfile.ZipFile(production) as archive:
        assert not any('FaultProbe' in name for name in archive.namelist())
    (folder/'jar-hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [production,jar]},indent=2))
    for name in ['payment_cancel_commit_fault.py', 'payment_commit_fault.py', 'payment_retry_fault.py', 'payment_saga_fault.py', 'payment_pg.py',
                 'compose.payment-saga-fault.yml', 'compose.local-three.yml', 'compose.yml', 'run.py', 'local_three.py']:
        shutil.copy2(ROOT/'perf'/name, folder/name)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java', folder)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentCommitFaultProbe.java', folder)
    env = ThreeApps('tfperf-pg-cancel-commit-'+folder.name, jar, folder, users=100)
    env.base += ['-f', str(ROOT/'perf/compose.payment-saga-fault.yml')]
    env.env.update(FAULT_DIR=str(folder/'gates'), PG_DATA_DIR=str(folder/'pg'))
    suite = None
    try:
        env.dc('up', '-d', 'pg', logfile=folder/'pg-start.log')
        env.start()
        suite = CancelSuite(folder, env)
        if not suite.run_commit(args.repeats): raise SystemExit(1)
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
