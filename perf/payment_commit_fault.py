#!/usr/bin/env python3
"""JDBC ACK-loss simulation; automatic real-process API and Worker cases."""
from payment_retry_fault import *
from payment_retry_fault import RetrySuite

class CommitSuite(RetrySuite):
    def commit_events(self, key, phase):
        rows=[]
        for file in self.gates.glob('commit-*.tsv'):
            for line in file.read_text().splitlines():
                parts=line.split('\t')
                if len(parts)==4 and parts[1]==key and parts[2]==phase:rows.append(parts)
        return rows

    def commit_loss(self, worker):
        payload,future=self.fixture('commit_ack_loss')
        c=self.current;key=c['pg_key'];app='app-2' if worker else 'app'
        (self.gates/('commit-target-'+app+'.arm')).write_text(f"{c['payment_id']},{c['booking_id']},{key}")
        if worker:
            self.control(key,['app-2']);self.scans(['app-2'])
        else:self.resume(key,'after_approve','app')
        self.wait(lambda:self.commit_events(key,'real_commit_returned'),'real MySQL commit returned')
        snapshot=self.snapshot('committed_but_application_has_no_ack')
        self.check(snapshot['payment']['status']=='COMPLETED' and snapshot['booking']['status']=='CONFIRMED','independent MySQL connection sees durable completion before injected JDBC error')
        (self.gates/(key+'-commit.resume')).touch()
        events=self.wait(lambda:self.commit_events(key,'spring_commit_failure'),'Spring observes commit failure')
        self.check(events[0][3]!='NONE','Spring commitFailure is non-null')
        self.check(len(self.commit_events(key,'jdbc_exception'))==1,'exactly one targeted JDBC exception')
        self.recovery_deadline=time.monotonic()+100
        self.terminal(True)
        if worker:self.resume(key,'after_approve','app')
        reply=future.result(30)
        self.check(reply['status']==200 and json.loads(reply['body'])['status']=='COMPLETED','API rereads committed result despite internal commit error')
        self.finish(payload)
        (self.gates/('commit-target-'+app+'.arm')).unlink(missing_ok=True)

    def run_commit(self,repeats):
        import traceback
        for name,worker in [('api_commit',False),('worker_commit',True)]:
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
    folder = ROOT/'test-results/payment-commit-fault'/(time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
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
    for name in ['payment_commit_fault.py', 'payment_retry_fault.py', 'payment_saga_fault.py', 'payment_pg.py',
                 'compose.payment-saga-fault.yml', 'compose.local-three.yml', 'compose.yml', 'run.py', 'local_three.py']:
        shutil.copy2(ROOT/'perf'/name, folder/name)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java', folder)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentCommitFaultProbe.java', folder)
    env = ThreeApps('tfperf-pg-commit-'+folder.name, jar, folder, users=100)
    env.base += ['-f', str(ROOT/'perf/compose.payment-saga-fault.yml')]
    env.env.update(FAULT_DIR=str(folder/'gates'), PG_DATA_DIR=str(folder/'pg'))
    suite = None
    try:
        env.dc('up', '-d', 'pg', logfile=folder/'pg-start.log')
        env.start()
        suite = CommitSuite(folder, env)
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
