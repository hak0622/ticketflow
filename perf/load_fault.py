#!/usr/bin/env python3
"""300/500 distinct users, actual process kill, real scheduled recovery under traffic."""
import csv
import hashlib
import json
import shutil
import subprocess
import time
import traceback
import uuid
import zipfile

from local_three import ThreeApps, ResourceMonitor
from local_three_fault import Suite
from run import ROOT, PoolMonitor, build_jar, command, metric

LAST_RESULT_DIR = None


def build_fault(folder):
    production = build_jar(ROOT, folder / 'build.log')
    command(['./gradlew', 'testClasses', '--offline'], logfile=folder / 'test-classes.log')
    saved = folder / 'fault-app.jar'
    shutil.copy2(production, saved)
    probes = list((ROOT / 'build/classes/java/test/studying/blog/fault').glob('LocalThreeFaultProbe*.class'))
    assert len(probes) >= 2
    with zipfile.ZipFile(production) as archive:
        assert not any('LocalThreeFaultProbe' in name for name in archive.namelist())
    with zipfile.ZipFile(saved, 'a') as archive:
        for p in probes:
            archive.write(p, 'BOOT-INF/classes/studying/blog/fault/' + p.name)
    return saved


def verify(env, cid, requests, target):
    deadline = time.monotonic() + 120
    history = []
    while True:
        rows = env.sql(f'SELECT status,COUNT(*) FROM booking_attempt WHERE concert_id={cid} GROUP BY status')
        states = {a: int(b) for a,b in (line.split('\t') for line in rows.splitlines())}
        history.append({'time_ms': time.time()*1000, 'states': states})
        if not (states.get('REQUESTED', 0) + states.get('RELEASE_PENDING', 0)) or time.monotonic() > deadline:
            break
        time.sleep(2)
    (env.results / 'settling.json').write_text(json.dumps(history, indent=2))
    attempts = env.sql(f'SELECT id,user_id,status,result_status,COALESCE(booking_id,0),retry_count FROM booking_attempt WHERE concert_id={cid} ORDER BY user_id')
    (env.results / 'attempts.tsv').write_text(attempts)
    booking_dump = env.sql(f'SELECT id,user_id,status FROM booking WHERE concert_id={cid} ORDER BY user_id')
    (env.results / 'bookings.tsv').write_text(booking_dump)
    booking_lines = [r.split('\t') for r in booking_dump.splitlines() if r]
    active = sum(r[2] in ('PENDING_PAYMENT', 'CONFIRMED') for r in booking_lines)
    remaining = int(env.redis('GET', f'seats:concert:{cid}'))
    anomaly = int(env.sql(f"""SELECT COUNT(*) FROM booking_attempt a LEFT JOIN booking b ON b.id=a.booking_id
        WHERE a.concert_id={cid} AND ((a.status='BOOKED' AND
        (b.id IS NULL OR b.user_id<>a.user_id OR b.concert_id<>a.concert_id OR b.status<>'PENDING_PAYMENT'))
        OR (a.status='RELEASED' AND a.booking_id IS NOT NULL))"""))
    args = []
    for r in requests:
        args += [r['id'], str(r['userId']), 'RELEASED' if r['id'] == target else 'HELD']
    lua = """local records={};local bad=0;for i=1,#ARGV,3 do
        local id=ARGV[i];local uid=ARGV[i+1];local expected=ARGV[i+2];local key='booking:attempt:'..id;
        local fields=redis.call('HGETALL',key);local state=redis.call('HGET',key,'state');
        local active=redis.call('GET','booking:active:concert:'..KEYS[1]..':user:'..uid);
        local admission=redis.call('EXISTS','admitted:concert:'..KEYS[1]..':user:'..uid);
        if state~=expected or redis.call('HGET',key,'owner')~=KEYS[1]..':'..uid then bad=bad+1 end;
        if expected=='HELD' and (active~=id or admission~=0) then bad=bad+1 end;
        if expected=='RELEASED' and (active or admission~=1 or redis.call('HGET',key,'releaseOutcome')~='RETURNED') then bad=bad+1 end;
        table.insert(records,{id=id,fields=fields,active=active or false,admission=admission});
        end;return cjson.encode({anomalies=bad,records=records})"""
    markers = json.loads(env.redis('EVAL', lua, 1, cid, *args))
    (env.results / 'redis-markers.json').write_text(json.dumps(markers, indent=2))
    target_row = env.sql(f"SELECT status,result_status FROM booking_attempt WHERE id='{target}'")
    actual_pairs = {(line.split('\t')[0], int(line.split('\t')[1])) for line in attempts.splitlines() if line}
    expected_pairs = {(r['id'],int(r['userId'])) for r in requests}
    checks = dict(exact_request_user_pairs=actual_pairs == expected_pairs,
        exact_terminal_states=states == {'BOOKED': len(requests)-1, 'RELEASED': 1},
        target_recovered=target_row == 'RELEASED\tBOOKING_FAILED',
        exact_bookings=len(booking_lines)==active==len(requests)-1,
        no_duplicate_bookings=len({r[1] for r in booking_lines})==len(booking_lines),
        no_oversell=0<=active<=len(requests), quantity_conserved=remaining+active==len(requests),
        exact_remaining=remaining==1, valid_links=anomaly==0, valid_redis_markers=markers['anomalies']==0)
    return dict(passed=all(checks.values()), checks=checks, remaining=remaining, active_bookings=active,
                attempt_states=states, link_anomalies=anomaly, marker_anomalies=markers['anomalies'])


def run_case(folder, jar, users, run_id):
    folder.mkdir()
    (folder/'gates').mkdir()
    env = ThreeApps(f'tfperf-load-fault-{run_id}-{users}', jar, folder, users=users)
    env.base += ['-f', str(ROOT/'perf/compose.local-three-fault.yml'), '-f', str(ROOT/'perf/compose.load-fault.yml')]
    env.env['FAULT_DIR'] = str(folder/'gates')
    monitors, suite, process, resources = {}, None, None, None
    row = {'users': users, 'capacity': users, 'status': 'FAIL', 'warmup': False}
    try:
        resources = ResourceMonitor(env.project, folder)
        env.start()
        suite = Suite(folder, env)
        suite.current = {}
        requests = [dict(userId=int(r['userId']), token=r['token'], id=str(uuid.uuid4()))
                    for r in list(csv.DictReader((folder/'users.csv').open()))[:users]]
        (folder/'requests.json').write_text(json.dumps(requests))
        target = requests[0]['id']
        witness = requests[1]['id']
        cid = env.concert(f'load_fault_{users}', users, 'api')
        row.update(concert_id=cid, target_request_id=target, witness_request_id=witness)
        suite.arm(target, 'after_reserve')
        suite.arm(witness, 'after_reserve', 'app-2')
        for service,url in env.metrics_urls.items():
            monitors[service] = PoolMonitor(url, folder/(service+'-pool.ndjson'))
            monitors[service].thread.start()
        with (folder/'k6.log').open('w') as logfile:
            process = subprocess.Popen(env.base + ['run','--rm','--no-deps','-T','k6','run',
                '-e', f'USERS={users}', '-e', f'CONCERT_ID={cid}', '/scripts/booking-fault.js'],
                env=env.env, cwd=ROOT, stdout=logfile, stderr=subprocess.STDOUT)
            suite.paused(target, 'after_reserve')
            suite.paused(witness, 'after_reserve')
            # Both actual Lua calls have finished inside their open DB transactions.
            # Avoid slow multi-query snapshots while waiting to inject the crash.
            suite.check(True, 'Target and app-2 witness reached real hold boundaries')
            suite.check(process.poll() is None, 'k6 still running at process kill')
            suite.kill('app')
            suite.resume(witness, 'after_reserve')
            row['k6_exit_code'] = process.wait(timeout=180)
        summary = json.loads((folder/'k6-summary.json').read_text())
        start = metric(summary,'user_started_at_ms','min')
        end = metric(summary,'user_finished_at_ms','max')
        row.update(booking_avg_ms=metric(summary,'booking_duration','avg',None),
            booking_p95_ms=metric(summary,'booking_duration','p(95)',None),
            user_avg_ms=metric(summary,'user_duration','avg',None), user_p95_ms=metric(summary,'user_duration','p(95)',None),
            booked=int(metric(summary,'booked','count')), recovered_failure=int(metric(summary,'recovered_failure','count')),
            terminal_error_rate=metric(summary,'terminal_errors','rate',None),
            transport_errors=int(metric(summary,'transport_errors','count')), technical_responses=int(metric(summary,'technical_responses','count')),
            http_error_rate=metric(summary,'http_req_failed','rate',None), total_posts=int(metric(summary,'http_reqs','count')),
            completed_users=int(metric(summary,'completed_users','count')), span_seconds=(end-start)/1000,
            completed_users_per_second=metric(summary,'completed_users','count')/((end-start)/1000))
        row['pools'] = {s:m.finish(start,end) for s,m in monitors.items()}
        monitors = {}
        row['verification'] = verify(env,cid,requests,target)
        suite.automatic_worker(target, excluded=('app',))
        events = suite.events(target)
        worker = next(e for e in events if e['phase']=='worker_end' and e['instance']!='app')
        held = next(e for e in events if e['phase']=='after_reserve')
        row['hold_to_worker_end_seconds'] = (int(worker['time_ms'])-int(held['time_ms']))/1000
        row['worker'] = worker
        # k6 JSON response records are kept individually, including every lost response and retry.
        responses = []
        for line in (folder/'k6.log').read_text().splitlines():
            pos=line.find('{"type":')
            if pos<0: continue
            try:
                value,_=json.JSONDecoder().raw_decode(line[pos:])
                responses.append(value)
            except ValueError: pass
        (folder/'responses.ndjson').write_text(''.join(json.dumps(r)+'\n' for r in responses))
        overlap = [r for r in responses if r['type']=='response' and r['request_id']!=target
            and abs(r['end_ms']-int(worker['time_ms']))<=5000 and r['result']=='BOOKED']
        row['booked_responses_within_5s_of_recovery'] = len(overlap)
        suite.check(len(overlap)>0, 'Other successful bookings occurred within five seconds of automatic recovery')
        timeline=[json.loads(l) for l in (folder/'timeline.ndjson').read_text().splitlines()]
        kill_time=next(e['time_ms'] for e in timeline if e['kind']=='kill_start')
        witness_paused=next(e for e in suite.events(witness) if e['phase']=='paused_after_reserve')
        witness_committed=next(e for e in suite.events(witness) if e['phase']=='after_commit')
        row['witness_open_transaction_at_kill']=int(witness_paused['time_ms'])<=kill_time<int(witness_committed['time_ms'])
        row['witness_committed_after_kill_ms']=int(witness_committed['time_ms'])-kill_time
        suite.check(row['witness_open_transaction_at_kill'], 'Different app had real open booking transaction at kill and committed afterwards')
        suite.check(any(r['type']=='response' and r['request_id']==witness and r['result']=='BOOKED' for r in responses),
                    'Witness booking survived crash on the other app')
        before_replay = suite.snapshot(cid,target,'before_terminal_replays')
        replies=[suite.request(s,cid,target) for s in ['app-2','app-3','app-2']]
        suite.check(all(r['status']==409 and json.loads(r['body']).get('status')=='BOOKING_FAILED' for r in replies),
                    'Recovered request replays failure on both surviving apps')
        suite.check(before_replay==suite.snapshot(cid,target,'after_terminal_replays'), 'Repeated terminal request changes no quantities')
        row['resources'] = resources.finish(start,end)
        resources = None
        telemetry = all(p['sample_count']>0 and p['observed_acquire_timeout_delta'] is not None for p in row['pools'].values())
        row['telemetry_complete'] = telemetry
        row['status'] = 'PASS' if row['k6_exit_code']==0 and row['verification']['passed'] and telemetry else 'FAIL'
    except Exception:
        row['error'] = traceback.format_exc()
        print(row['error'], flush=True)
    finally:
        if process and process.poll() is None:
            for p in (folder/'gates').glob('*.arm'): p.with_suffix('.resume').touch()
            try: process.wait(timeout=160)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=15)
        for m in monitors.values(): m.finish(0,time.time()*1000)
        if resources: resources.finish(0,time.time()*1000)
        if suite: suite.executor.shutdown(wait=True)
        (folder/'result.json').write_text(json.dumps(row,indent=2))
        env.close()
    return row


def main():
    global LAST_RESULT_DIR
    run_id=time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    folder=ROOT/'build/load-fault'/run_id
    folder.mkdir(parents=True)
    LAST_RESULT_DIR = folder
    print('Results:',folder,flush=True)
    jar=build_fault(folder)
    hashes={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (ROOT/'src/main').rglob('*') if p.is_file()}
    (folder/'environment.json').write_text(json.dumps(dict(source_sha256=hashes,jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        pattern='70% initial burst; 30% distributed over seconds 20..40; one target retry delayed 40s',
        worker_interval_seconds=5, requested_grace_seconds=30, manual_worker_invocations=0,
        docker=json.loads(command(['docker','info','--format','{{json .}}'])[0])),indent=2))
    for name in ['load_fault.py','booking-fault.js','compose.load-fault.yml','local_three_fault.py','local_three.py','run.py',
                 'compose.yml','compose.local-three.yml','compose.local-three-fault.yml','nginx.local-three.conf']:
        shutil.copy2(ROOT/'perf'/name,folder/name)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/LocalThreeFaultProbe.java',folder)
    results=[]
    for users in [300,500]:
        print('START',users,flush=True)
        result=run_case(folder/str(users),jar,users,run_id)
        results.append(result)
        (folder/'results.json').write_text(json.dumps(results,indent=2))
        print(users,result['status'],result.get('verification'),flush=True)
    after={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
           for p in (ROOT/'src/main').rglob('*') if p.is_file()}
    (folder/'production-source-verification.json').write_text(json.dumps({'unchanged':hashes==after},indent=2))
    if not all(r['status']=='PASS' for r in results) or hashes!=after: raise SystemExit(1)


if __name__=='__main__':
    try:
        main()
    finally:
        # Preserve successes AND failures outside Gradle's cleanable build directory.
        if LAST_RESULT_DIR is not None:
            archive = ROOT/'test-results/load-fault'/LAST_RESULT_DIR.name
            shutil.copytree(LAST_RESULT_DIR, archive)
            print('Permanent local archive:', archive, flush=True)
