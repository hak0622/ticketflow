#!/usr/bin/env python3
"""Disposable HTTP PG, durable SQLite ledger, stable idempotency and controllable queries."""
import json
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path('/data')
ROOT.mkdir(exist_ok=True)
LOCK = threading.RLock()
DB = sqlite3.connect(ROOT / 'pg.sqlite', check_same_thread=False)
DB.execute('PRAGMA journal_mode=WAL')
DB.execute('PRAGMA synchronous=FULL')
DB.executescript('''CREATE TABLE IF NOT EXISTS payments(key TEXT PRIMARY KEY, order_id TEXT UNIQUE, amount INTEGER, status TEXT);
CREATE TABLE IF NOT EXISTS operations(op TEXT, token TEXT, body TEXT, key TEXT, PRIMARY KEY(op,token));
CREATE TABLE IF NOT EXISTS requests(id INTEGER PRIMARY KEY, at REAL, instance TEXT, method TEXT, path TEXT, body TEXT, token TEXT, result TEXT, effect INTEGER);''')

def control():
    try: return json.loads((ROOT / 'control.json').read_text())
    except FileNotFoundError: return {}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, code, value):
        body = json.dumps(value).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body))); self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass
    def do_GET(self): self.run_request()
    def do_POST(self): self.run_request()
    def run_request(self):
        with LOCK:
            if self.path == '/snapshot':
                tables = {name: [dict(zip([x[0] for x in cursor.description], row)) for row in cursor]
                          for name in ['payments', 'operations', 'requests']
                          for cursor in [DB.execute('SELECT * FROM ' + name)]}
                self.respond(200, tables); return
            if self.path == '/health': self.respond(200, {'ok': True}); return
            instance = self.headers.get('X-Fault-Instance', '')
            token = self.headers.get('Idempotency-Key', '')
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))) or b'{}')
            text = json.dumps(body, sort_keys=True)
            code, effect = 200, 0
            try:
                if self.path == '/v1/payments/confirm':
                    op, key = 'approve', body['paymentKey']
                else:
                    key = self.path.split('/')[3]
                    op = 'cancel' if self.path.endswith('/cancel') else 'lookup'
                cfg = control().get(key, {})
                if op == 'lookup' and instance not in cfg.get('lookup', []):
                    code, response = 503, {'error': 'test lookup gate'}
                elif op == 'cancel' and cfg.get('block_cancel', False):
                    code, response = 503, {'error': 'test cancellation gate'}
                else:
                    if op in ('approve','cancel'):
                        assert token, 'Missing idempotency token'
                        prior = DB.execute('SELECT body,key FROM operations WHERE op=? AND token=?',(op,token)).fetchone()
                        if prior: assert prior == (text,key), 'Idempotency payload changed'
                        else:
                            if op == 'approve':
                                DB.execute('INSERT INTO payments VALUES(?,?,?,?)',(key,body['orderId'],body['amount'],'DONE'))
                            else:
                                row = DB.execute('SELECT amount,status FROM payments WHERE key=?',(key,)).fetchone()
                                assert row and row[0] == body['cancelAmount'], 'Full cancellation amount mismatch'
                                assert row[1] == 'DONE', 'Duplicate effect with different idempotency token'
                                DB.execute("UPDATE payments SET status='CANCELED' WHERE key=?",(key,))
                            DB.execute('INSERT INTO operations VALUES(?,?,?,?)',(op,token,text,key)); effect=1
                    row = DB.execute('SELECT order_id,amount,status FROM payments WHERE key=?',(key,)).fetchone()
                    if row:
                        response = dict(paymentKey=key,orderId=row[0],totalAmount=row[1],balanceAmount=0 if row[2]=='CANCELED' else row[1],status=row[2],method='CARD')
                    else: code,response=404,{'error':'not found'}
            except Exception as error:
                DB.rollback(); code,response,effect=409,{'error':str(error)},0
            DB.execute('INSERT INTO requests(at,instance,method,path,body,token,result,effect) VALUES(?,?,?,?,?,?,?,?)',
                       (time.time(),instance,self.command,self.path,text,token,json.dumps(response),effect))
            DB.commit() # Ledger and response snapshot are durable before the response leaves PG.
            self.respond(code,response)

ThreadingHTTPServer(('0.0.0.0',5000),Handler).serve_forever()
