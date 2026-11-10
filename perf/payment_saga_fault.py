#!/usr/bin/env python3
"""Real payment SIGKILL and scheduler races; never invokes recovery/schedulers manually."""
import argparse
import concurrent.futures
import csv
import hashlib
import json
import re
from pathlib import Path
import shutil
import sys
import time
import traceback
import urllib.request
import urllib.error
import uuid
import zipfile

from local_three import ThreeApps
from run import ROOT, build_jar, command, http


class Suite:
    def __init__(self, folder, env):
        self.folder, self.env = folder, env
        self.gates = folder / 'gates'
        self.pgdata = folder / 'pg'
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=12)
        self.token = next(csv.DictReader((folder / 'users.csv').open()))['token']
        self.config, self.results = {}, []
        self.current = None
        self.started_trial = False
        port = env.dc('port','pg','5000')[0].strip().rsplit(':',1)[1]
        self.pgurl = 'http://127.0.0.1:'+port

    def record(self, kind, **values):
        event = dict(time_ms=time.time()*1000,kind=kind,**values)
        with (self.folder/'timeline.ndjson').open('a') as file: file.write(json.dumps(event)+'\n')
        return event

    def check(self, condition, message):
        self.record('assertion',passed=bool(condition),message=message)
        if not condition: raise AssertionError(message)

    def wait(self, predicate, message, timeout=180):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=predicate()
            if value: return value
            time.sleep(.15)
        raise AssertionError('Timed out: '+message)

    def url(self, app):
        port=self.env.dc('port',app,'8080')[0].strip().rsplit(':',1)[1]
        return 'http://127.0.0.1:'+port

    def request(self, app, path, body=None, headers=None):
        req=urllib.request.Request(self.url(app)+path,data=json.dumps(body).encode() if body is not None else b'',
            headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json',**(headers or {})},method='POST')
        try:
            with urllib.request.urlopen(req,timeout=260) as response: result={'status':response.status,'body':response.read().decode()}
        except urllib.error.HTTPError as error: result={'status':error.code,'body':error.read().decode()}
        except Exception as error: result={'status':0,'body':str(error)}
        self.record('http',app=app,path=path,**result)
        return result

    def pg(self): return json.loads(http(self.pgurl+'/snapshot'))

    def control(self,key,lookup,block_cancel=False):
        self.config[key]={'lookup':lookup,'block_cancel':block_cancel}
        temp=self.pgdata/'control.tmp';temp.write_text(json.dumps(self.config));temp.replace(self.pgdata/'control.json')
        self.record('pg_control',key=key,config=self.config[key])

    def arm(self,key,phase,app): (self.gates/f'{key}-{phase}-{app}.arm').touch()
    def resume(self,key,phase,app): (self.gates/f'{key}-{phase}-{app}.resume').touch()
    def events(self,key,phase=None,app=None):
        rows=[]
        for file in self.gates.glob('events-*.tsv'):
            for line in file.read_text().splitlines():
                fields=line.split('\t')
                if len(fields)==6 and fields[3]==key and (phase is None or fields[2]==phase) and (app is None or fields[1]==app):
                    rows.append(dict(zip(['time_ms','app','phase','key','thread','detail'],fields)))
        return rows
    def paused(self,key,phase,app,timeout=180):
        result=self.wait(lambda:self.events(key,'paused_'+phase,app),phase+' '+app,timeout)
        print('GATE',phase,app,flush=True);return result
    def json_sql(self,query):
        # mysql -B escapes JSON's backslashes. HEX transports the scalar without double escaping.
        return json.loads(bytes.fromhex(self.env.sql('SELECT HEX(('+query+'))')).decode())

    def restart(self):
        if self.started_trial:
            self.env.dc('restart','app','app-2','app-3')
            self.record('fixture_jvm_restart',reason='independent trial; no inherited Circuit Breaker state')
        else:
            self.env.dc('up','-d','app','app-2','app-3')
            self.started_trial=True
        for app in ['app','app-2','app-3']:
            port=self.env.dc('port',app,'8081')[0].strip().rsplit(':',1)[1]
            def ready():
                try: return json.loads(http('http://127.0.0.1:'+port+'/actuator/health')).get('status')=='UP'
                except Exception: return False
            self.wait(ready,app+' healthy')

    def scans(self,allowed):
        for app in ['app','app-2','app-3']:
            path=self.gates/('scan-'+app+'.block')
            if app in allowed:path.unlink(missing_ok=True)
            else:path.touch()
        self.record('automatic_scan_gates',allowed=allowed)

    def fixture(self,label):
        self.scans([])
        self.restart()
        cid=self.env.concert(label,3,'api');aid=str(uuid.uuid4());key=str(uuid.uuid4());order=str(uuid.uuid4())
        self.current.update(concert_id=cid,attempt_id=aid,pg_key=key,order_id=order)
        self.control(key,[])
        booking=self.request('app',f'/api/concerts/{cid}/booking',headers={'Idempotency-Key':aid})
        self.check(booking['status']==200,'real Booking API succeeded')
        bid=int(self.env.sql(f'SELECT id FROM booking WHERE concert_id={cid} AND user_id=1'))
        (self.gates/f'booking-{bid}.key').write_text(key)
        self.current['booking_id']=bid
        self.arm(key,'after_approve','app')
        payload={'paymentKey':key,'orderId':order,'amount':90000}
        future=self.pool.submit(self.request,'app',f'/api/concerts/{cid}/payment/toss-confirm',payload)
        self.paused(key,'after_approve','app')
        pid=int(self.env.sql(f"SELECT id FROM payment WHERE idempotency_key='{order}'"))
        self.current['payment_id']=pid
        self.check(any(x['key']==key and x['status']=='DONE' for x in self.pg()['payments']),'PG approval committed before gate')
        snap=self.snapshot('before_fixture')
        self.check(snap['payment']['status']=='PENDING' and snap['booking']['status']=='PENDING_PAYMENT','approval not yet applied in DB')
        return payload,future

    def cancel_fixture(self,race=False):
        c=self.current;pid,bid,key=c['payment_id'],c['booking_id'],c['pg_key']
        sql=f"START TRANSACTION; SELECT id FROM booking WHERE id={bid} FOR UPDATE; UPDATE payment SET recovery_phase='CANCEL_REQUIRED',cancel_reason='TicketFlow booking cannot be finalized',version=version+1 WHERE id={pid};"
        if race:
            sql+=f"UPDATE booking SET created_at=DATE_SUB(created_at,INTERVAL 31 MINUTE) WHERE id={bid};"
        sql+='COMMIT;';self.env.sql(sql)
        self.record('fixture_only_sql',sql=sql,reason='initial cancellation intent; not generated through public API')
        self.snapshot('cancellation_intent_committed')

    def past_outbox_fixture(self):
        c=self.current;bid,key=c['booking_id'],c['pg_key']
        sql=f"START TRANSACTION; SELECT id FROM booking WHERE id={bid} FOR UPDATE;"
        sql+=f"INSERT INTO payment(booking_id,user_id,concert_id,idempotency_key,amount,status,payment_method,created_at,retry_count,review_required,version,provider,recovery_phase) VALUES({bid},1,{c['concert_id']},'past-{key}',90000,'FAILED','MOCK',NOW(),0,false,0,'MOCK','NONE'); SET @past=LAST_INSERT_ID();"
        payload=json.dumps({'bookingId':bid,'concertId':c['concert_id'],'paymentId':0,'userId':1})
        sql+=f"INSERT INTO payment_compensation_outbox(event_type,payload,status,retry_count,created_at,version) VALUES('payment.compensation',JSON_SET('{payload}','$.paymentId',@past),'PENDING',0,NOW(),0);"
        sql+='COMMIT;';self.env.sql(sql)
        self.record('fixture_only_sql',sql=sql,reason='historical failure fixture staged after B/C captured their responses; prevents single scheduler gate deadlock')

    def snapshot(self,label):
        c=self.current;bid,pid,cid,aid=c['booking_id'],c['payment_id'],c['concert_id'],c['attempt_id']
        payment=self.json_sql(f"SELECT JSON_OBJECT('id',id,'status',status,'phase',recovery_phase,'booking_id',booking_id,'user_id',user_id,'concert_id',concert_id,'amount',amount,'order',idempotency_key,'key',pg_payment_key,'confirm_key',confirm_key,'cancel_key',cancel_key,'retry_count',retry_count,'next_retry_at',next_retry_at) FROM payment WHERE id={pid}")
        booking=self.json_sql(f"SELECT JSON_OBJECT('id',id,'status',status,'user_id',user_id,'concert_id',concert_id) FROM booking WHERE id={bid}")
        attempt=self.json_sql(f"SELECT JSON_OBJECT('id',id,'status',status,'booking_id',booking_id,'result',result_status) FROM booking_attempt WHERE id='{aid}'")
        outboxes=self.json_sql(f"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id',id,'status',status,'retry_count',retry_count,'payload',payload)),JSON_ARRAY()) FROM payment_compensation_outbox WHERE JSON_EXTRACT(payload,'$.bookingId')={bid}")
        history=self.json_sql(f"SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('id',id,'status',status,'booking_id',booking_id,'user_id',user_id,'concert_id',concert_id,'amount',amount)),JSON_ARRAY()) FROM payment WHERE booking_id={bid}")
        fields=self.env.redis('HGETALL','booking:attempt:'+aid).splitlines()
        counts=self.json_sql(f"SELECT JSON_OBJECT('bookings',(SELECT COUNT(*) FROM booking WHERE concert_id={cid}),'attempts',(SELECT COUNT(*) FROM booking_attempt WHERE concert_id={cid}),'order_payments',(SELECT COUNT(*) FROM payment WHERE idempotency_key='{c['order_id']}'))")
        result=dict(counts=counts,payment=payment,payment_history=history,booking=booking,attempt=attempt,outboxes=outboxes,
            remaining=int(self.env.redis('GET',f'seats:concert:{cid}')),
            marker=dict(zip(fields[::2],fields[1::2])),active=self.env.redis('GET',f'booking:active:concert:{cid}:user:1'),
            valid_bookings=int(self.env.sql(f"SELECT COUNT(*) FROM booking WHERE concert_id={cid} AND status<>'CANCELLED'")),pg=self.pg())
        self.record('snapshot',label=label,key=c['pg_key'],values=result)
        return result

    def binlog_position(self):
        row=self.env.sql('SHOW BINARY LOG STATUS').split('\t')
        assert re.fullmatch(r'[A-Za-z0-9_.-]+',row[0])
        return row[0],int(row[1])

    def audit_no_reversal(self,start):
        end=self.binlog_position()
        self.check(start[0]==end[0],'binlog interval does not rotate')
        path=self.folder/(self.current['pg_key']+'-binlog.txt')
        container=self.env.dc('ps','-q','mysql')[0].strip()
        raw=self.folder/(self.current['pg_key']+'-binlog.raw')
        command(['docker','cp',container+':/var/lib/mysql/'+start[0],str(raw)])
        command(['mysqlbinlog','--version'],logfile=self.folder/'binlog-decoder-version.txt')
        command(['mysqlbinlog','--base64-output=DECODE-ROWS','-vv',
            '--start-position='+str(start[1]),'--stop-position='+str(end[1]),str(raw)],logfile=path)
        changes=[]
        for table,id_field,forbidden in [('payment','payment_id','COMPLETED'),('booking','booking_id','CONFIRMED')]:
            cols=self.json_sql("SELECT JSON_ARRAYAGG(JSON_OBJECT('name',COLUMN_NAME,'ordinal',ORDINAL_POSITION,'type',COLUMN_TYPE)) FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='"+table+"'")
            pk=next(x['ordinal'] for x in cols if x['name']=='id')
            status=next(x for x in cols if x['name']=='status')
            enum=re.findall(r"'([^']+)'",status['type'])
            pattern=r'### (?:UPDATE|INSERT INTO) `ticketflow_perf`.`'+table+r'`\n(.*?)(?=### (?:UPDATE|INSERT INTO|DELETE FROM)|# at|\Z)'
            for match in re.finditer(pattern,path.read_text(),re.S):
                body=match.group(1)
                identity=re.search(r'###   @'+str(pk)+r'=(\d+)',body)
                if not identity or int(identity.group(1))!=self.current[id_field]:continue
                after=body.split('### SET')[-1]
                value=re.search(r'###   @'+str(status['ordinal'])+r'=([^\n]+)',after)
                if not value:continue
                raw=value.group(1).split(' /*')[0]
                state=enum[int(raw)-1] if raw.isdigit() and enum else raw.strip("'")
                changes.append(dict(table=table,id=self.current[id_field],status=state))
                self.check(state!=forbidden,'binlog: no '+forbidden+' after cancellation intent')
            self.check(any(x['table']==table for x in changes),'binlog includes terminal '+table+' update')
        (self.folder/(self.current['pg_key']+'-state-changes.json')).write_text(json.dumps(changes,indent=2))
        self.record('binlog_audit',start=start,end=end,changes=changes)

    def kill_a(self):
        self.record('kill_start',app='app');self.env.dc('kill','-s','SIGKILL','app');self.recovery_deadline=time.monotonic()+180
        container=self.env.dc('ps','-a','-q','app')[0].strip()
        state=json.loads(command(['docker','inspect','--format','{{json .State}}',container])[0])
        self.record('kill_complete',app='app',state=state)
        self.check(state['ExitCode']==137 and not state['OOMKilled'],'SIGKILL 137 and no OOM')

    def terminal(self,paid):
        c=self.current;pid,bid=c['payment_id'],c['booking_id']
        wanted='COMPLETED' if paid else 'CANCELED'
        def budget():
            remaining=self.recovery_deadline-time.monotonic()
            if remaining<=0:raise AssertionError('Automatic recovery exceeded total 180 seconds')
            return remaining
        self.wait(lambda:self.env.sql(f'SELECT status FROM payment WHERE id={pid}')==wanted,'automatic Payment '+wanted,budget())
        self.wait(lambda:self.env.sql(f'SELECT status FROM booking WHERE id={bid}')==('CONFIRMED' if paid else 'CANCELLED'),'automatic Booking terminal',budget())
        if not paid:
            self.wait(lambda:self.env.sql(f"SELECT status FROM booking_attempt WHERE id='{c['attempt_id']}'")=='RELEASED','automatic ticket release',budget())
            self.wait(lambda:self.env.sql(f"SELECT COUNT(*) FROM payment_compensation_outbox WHERE JSON_EXTRACT(payload,'$.bookingId')={bid} AND status<>'PUBLISHED'")=='0','automatic Outbox completion',budget())
        return self.validate(paid,'terminal')

    def validate(self,paid,label):
        c=self.current;s=self.snapshot(label);key=c['pg_key'];pgrows=[r for r in s['pg']['payments'] if r['key']==key]
        self.check(len(pgrows)==1 and pgrows[0]['status']==('DONE' if paid else 'CANCELED'),'PG money outcome matches Booking')
        self.check(s['counts']==dict(bookings=1,attempts=1,order_payments=1),'exactly one Booking, attempt and Payment per order')
        self.check(s['payment']['status']==('COMPLETED' if paid else 'CANCELED'),'Payment final status')
        self.check(s['booking']['status']==('CONFIRMED' if paid else 'CANCELLED'),'Booking final status')
        self.check(s['attempt']['status']==('BOOKED' if paid else 'RELEASED'),'attempt final status')
        self.check(s['remaining']==(2 if paid else 3) and s['remaining']+s['valid_bookings']==3,'exact stock conservation')
        self.check(s['marker'].get('state')==('HELD' if paid else 'RELEASED'),'request marker terminal')
        self.check(s['active']==(c['attempt_id'] if paid else ''),'active ownership terminal')
        self.check(s['payment']['booking_id']==c['booking_id'] and s['attempt']['booking_id']==c['booking_id'],'DB relationship retained')
        self.check(s['payment']['key']==key and s['payment']['order']==c['order_id'] and s['payment']['amount']==90000 and s['payment']['user_id']==1 and s['payment']['concert_id']==c['concert_id'],'immutable Payment identity')
        operations=[x for x in s['pg']['operations'] if x['key']==key]
        self.check(sum(x['op']=='approve' for x in operations)==1,'one PG approval effect')
        self.check(sum(x['op']=='cancel' for x in operations)==int(not paid),'one or zero PG cancel effect')
        for op in operations:
            self.check(op['token']==s['payment']['confirm_key' if op['op']=='approve' else 'cancel_key'],'stored PG idempotency key used')
        self.check(len(s['outboxes'])==(0 if paid else 2 if c['case'].startswith('race') else 1),'exact distinct Outbox count')
        current_events=[event for event in s['outboxes'] if (json.loads(event['payload']) if isinstance(event['payload'],str) else event['payload'])['paymentId']==c['payment_id']]
        self.check(len(current_events)==int(not paid),'one cancellation Outbox for current Payment, no duplicate events')
        for event in s['outboxes']:
            payload=json.loads(event['payload']) if isinstance(event['payload'],str) else event['payload']
            history={row['id']:row for row in s['payment_history']}
            source=history.get(payload['paymentId'])
            self.check(source is not None and source['booking_id']==c['booking_id'] and source['status'] in ('FAILED','CANCELED'),'Outbox linked to valid terminal Payment')
        for request in s['pg']['requests']:
            if request['method']=='POST' and (request['path'].endswith('/'+key+'/cancel') or (request['path'].endswith('/confirm') and json.loads(request['body']).get('paymentKey')==key)):
                self.check(request['token']==s['payment']['cancel_key' if request['path'].endswith('/cancel') else 'confirm_key'],'all POST attempts use stable stored PG key')
        if not paid:
            self.check(s['marker'].get('releaseOutcome')=='RETURNED','canonical request returned marker')
            self.check(sum(e['detail']=='RETURNED' for e in self.events(key,'release_result'))==1,'exactly one successful return Lua effect')
        return s

    def replays(self,payload,paid):
        c=self.current
        self.scans(['app-2','app-3'] if c['case'] in ('approve','cancel') else ['app','app-2','app-3'])
        observe_start=time.time()*1000
        tasks=[self.pool.submit(self.request,app,f"/api/concerts/{c['concert_id']}/payment/toss-confirm",payload)
               for app in ['app-2','app-3'] for _ in range(3)]
        for task in tasks:
            response=task.result(30)
            self.check(response['status']==200 and json.loads(response['body'])['status']==('COMPLETED' if paid else 'CANCELED'),'duplicate request reuses terminal response')
        time.sleep(11) # observation window, not a trigger for fault injection
        self.validate(paid,'after_two_worker_periods_and_replays')
        for app in ['app-2','app-3']:
            cycles=[event for event in self.events('global','scan_end',app) if float(event['time_ms'])>=observe_start]
            self.check(len(cycles)>=2,'at least two actual automatic Worker scans after recovery: '+app)

    def approval_crash(self):
        payload,future=self.fixture('pg_approval_crash')
        self.kill_a();self.check(future.result(30)['status']==0,'original response lost on actual SIGKILL')
        key=self.current['pg_key'];self.control(key,['app-2']);self.scans(['app-2'])
        self.terminal(True)
        events=self.wait(lambda:self.events(key,'worker_end','app-2'),'B scheduled recovery completion')
        self.check(any('scheduling' in e['thread'] for e in events),'B automatic scheduling thread')
        self.replays(payload,True)

    def cancellation_crash(self):
        payload,future=self.fixture('pg_cancel_crash');key=self.current['pg_key']
        self.arm(key,'after_cancel','app');self.cancel_fixture();self.control(key,['app']);self.scans(['app'])
        self.paused(key,'after_cancel','app')
        s=self.snapshot('pg_canceled_before_db_commit')
        self.check(s['payment']['status']=='PENDING' and s['remaining']==2 and not s['outboxes'],'cancel not applied to DB or inventory')
        self.kill_a();self.check(future.result(30)['status']==0,'original response lost on actual SIGKILL')
        self.control(key,['app-2']);self.scans(['app-2']);self.terminal(False)
        self.wait(lambda:self.events(key,'worker_end','app-2'),'B automatic cancel recovery')
        self.check(any('scheduling' in e['thread'] for e in self.events(key,'worker_end','app-2')),'B automatic scheduling thread')
        self.replays(payload,False)

    def race(self,late):
        payload,future=self.fixture('pg_race');key=self.current['pg_key']
        self.arm(key,'after_lookup','app-2');self.arm(key,'after_lookup','app-3')
        self.arm(key,'expiry','app');self.arm(key,'outbox','app-3');self.arm(key,'lock_expiry','app')
        self.cancel_fixture(race=True);audit_start=self.binlog_position();self.control(key,['app-2','app-3'],True);self.scans(['app-2','app-3'])
        self.paused(key,'after_lookup','app-2');self.paused(key,'after_lookup','app-3')
        self.check(not self.events(key,'worker_end','app-2') and not self.events(key,'worker_end','app-3'),'B and C automatic recoveries overlap on same request')
        self.past_outbox_fixture()
        self.scans(['app','app-2','app-3'])
        self.resume(key,'after_lookup','app-3')
        self.wait(lambda:self.events(key,'worker_end','app-3'),'C finishes with cancellation still uncertain')
        self.paused(key,'expiry','app');self.paused(key,'outbox','app-3')
        s=self.snapshot('all_competing_flows_ready')
        self.check(s['payment']['status']=='PENDING' and s['remaining']==2 and s['outboxes'][0]['retry_count']==0,'no return and deferred Outbox retry count intact')
        self.resume(key,'expiry','app');self.paused(key,'lock_expiry','app',15)
        try:
            self.resume(key,'after_lookup','app-2');self.resume(key,'outbox','app-3')
            if not late:self.resume(key,'after_approve','app')
            bid=self.current['booking_id']
            query=f"SELECT JSON_OBJECT('count',COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID),'edges',JSON_ARRAYAGG(JSON_OBJECT('waiting',w.REQUESTING_ENGINE_TRANSACTION_ID,'blocking',w.BLOCKING_ENGINE_TRANSACTION_ID,'index',l.INDEX_NAME,'data',l.LOCK_DATA))) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_NAME='booking' AND l.LOCK_DATA='{bid}'"
            def waits():
                evidence=self.json_sql(query)
                return evidence if evidence['count'] >= (2 if late else 3) else None
            evidence=self.wait(waits, 'all competing MySQL Booking row waits',2)
            self.record('mysql_booking_waiters',**evidence)
        finally: self.resume(key,'lock_expiry','app')
        self.wait(lambda:self.events(key,'expiry_deferred','app'),'expiry deferred while Payment unresolved')
        self.wait(lambda:self.events(key,'outbox_deferred','app-3'),'Outbox deferred while Payment unresolved')
        if not late:
            response=future.result(30);self.check(response['status']==200 and json.loads(response['body'])['status']=='PENDING','old DONE cannot override cancellation intent')
        s=self.snapshot('before_allowing_pg_cancel')
        self.check(s['payment']['status']=='PENDING' and s['remaining']==2 and all(x['status']=='PENDING' and x['retry_count']==0 for x in s['outboxes']),'all compensation protected before PG cancel confirmation')
        self.recovery_deadline=time.monotonic()+180
        self.control(key,['app','app-2','app-3'],False)
        self.terminal(False)
        if late:
            self.resume(key,'after_approve','app');response=future.result(30)
            self.check(response['status']==200 and json.loads(response['body'])['status']=='CANCELED','old DONE after completion remains CANCELED')
        self.replays(payload,False)
        self.audit_no_reversal(audit_start)

    def run(self,repeats,cases):
        methods={'approve':self.approval_crash,'cancel':self.cancellation_crash,
                 'race_pending':lambda:self.race(False),'race_terminal':lambda:self.race(True)}
        for name,test in methods.items():
            if name not in cases:continue
            for trial in range(1,repeats+1):
                self.current={'case':name,'trial':trial,'status':'RUNNING','started_at_ms':time.time()*1000}
                print('START',name,trial,flush=True)
                try:test();self.current['status']='SUCCESS'
                except Exception as error:
                    self.current.update(status='FAILURE',error=str(error),traceback=traceback.format_exc())
                    if 'payment_id' in self.current:
                        try:self.snapshot('failure_evidence')
                        except Exception as evidence:self.current['snapshot_error']=str(evidence)
                    print('FAILURE',name,trial,str(error),flush=True)
                finally:
                    key=self.current.get('pg_key','')
                    for arm in self.gates.glob(key+'-*.arm'):arm.with_suffix('.resume').touch()
                    self.scans(['app','app-2','app-3'])
                    self.current['finished_at_ms']=time.time()*1000
                    self.results.append(self.current)
                    (self.folder/'results.json').write_text(json.dumps(self.results,indent=2))
                    print(self.current['status'],name,trial,flush=True)
        return all(x['status']=='SUCCESS' for x in self.results)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--cases',default='approve,cancel,race_pending,race_terminal')
    args=parser.parse_args();assert 1<=args.repeats<=3
    assert args.cases and set(args.cases.split(',')) <= {'approve','cancel','race_pending','race_terminal'}
    folder=ROOT/'test-results/payment-saga-fault'/(time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
    folder.mkdir(parents=True);(folder/'gates').mkdir();(folder/'pg').mkdir()
    print('Results:',folder,flush=True)
    (folder/'command.txt').write_text('PYTHONPYCACHEPREFIX=/tmp/ticketflow-pycache python3 '+' '.join(sys.argv)+'\n')
    source={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'src/main').rglob('*') if p.is_file()}
    (folder/'environment.json').write_text(json.dumps(dict(source_sha256=source,manual_worker_invocations=0,repeats=args.repeats,cases=args.cases),indent=2))
    production=build_jar(ROOT,folder/'build.log')
    command(['./gradlew','testClasses','--offline'],logfile=folder/'test-classes.log')
    jar=folder/'fault-app.jar';shutil.copy2(production,jar)
    with zipfile.ZipFile(jar,'a') as archive:
        for p in (ROOT/'build/classes/java/test/studying/blog/fault').glob('PaymentSagaFaultProbe*.class'):
            archive.write(p,'BOOT-INF/classes/studying/blog/fault/'+p.name)
    with zipfile.ZipFile(production) as archive:assert not any('PaymentSagaFaultProbe' in x for x in archive.namelist())
    (folder/'jar-hashes.json').write_text(json.dumps({str(p.name):hashlib.sha256(p.read_bytes()).hexdigest() for p in [production,jar]},indent=2))
    for name in ['payment_saga_fault.py','payment_pg.py','compose.payment-saga-fault.yml','compose.local-three.yml','compose.yml','run.py','local_three.py']:
        shutil.copy2(ROOT/'perf'/name,folder/name)
    shutil.copy2(ROOT/'src/test/java/studying/blog/fault/PaymentSagaFaultProbe.java',folder)
    env=ThreeApps('tfperf-pg-fault-'+folder.name,jar,folder,users=100)
    env.base+=['-f',str(ROOT/'perf/compose.payment-saga-fault.yml')]
    env.env.update(FAULT_DIR=str(folder/'gates'),PG_DATA_DIR=str(folder/'pg'))
    suite=None
    try:
        env.dc('up','-d','pg',logfile=folder/'pg-start.log')
        env.start();suite=Suite(folder,env)
        if not suite.run(args.repeats,args.cases.split(',')):raise SystemExit(1)
    finally:
        if suite:
            for arm in suite.gates.glob('*.arm'):arm.with_suffix('.resume').touch()
            for block in suite.gates.glob('scan-*.block'):block.unlink(missing_ok=True)
            suite.pool.shutdown(wait=True)
            try:(folder/'pg-final.json').write_text(json.dumps(suite.pg(),indent=2))
            except Exception:pass
        env.dc('logs','--no-color','pg',logfile=folder/'pg.log',check=False)
        env.close()
        current={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'src/main').rglob('*') if p.is_file()}
        (folder/'summary.json').write_text(json.dumps({'results':suite.results if suite else [],'success':sum(x['status']=='SUCCESS' for x in suite.results) if suite else 0,'failure':sum(x['status']=='FAILURE' for x in suite.results) if suite else 0,'manual_worker_invocations':0,'production_source_unchanged':current==source,'external_pg_called':False,'pg_commit_ack_loss_tested':False},indent=2))
        assert current==source,'Production source changed during test'

if __name__=='__main__':main()
