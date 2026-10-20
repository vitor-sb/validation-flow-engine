#!/usr/bin/env python3
"""Closed-loop load driver (stdlib only). Usage: load.py BASE_URL API_KEY CONCURRENCY DURATION_S [WARMUP_S]
Creates+activates a light flow (START -> VALIDATION loadtest-fake -> END), then POSTs executions."""
import http.client, json, sys, threading, time, urllib.parse

base, key, conc, dur = sys.argv[1], sys.argv[2], int(sys.argv[3]), float(sys.argv[4])
warm = float(sys.argv[5]) if len(sys.argv) > 5 else 5.0
u = urllib.parse.urlparse(base)
H = {"X-API-Key": key, "Content-Type": "application/json"}


def call(c, method, path, body=None):
    c.request(method, path, json.dumps(body) if body is not None else None, H)
    r = c.getresponse()
    return r.status, r.read()


c = http.client.HTTPConnection(u.hostname, u.port)
flow_key = "loadtest-%d" % int(time.time())
s, b = call(c, "POST", "/api/v1/flows", {
    "flowKey": flow_key, "userType": "PF", "context": "LOAD", "displayName": "load", "inputContract": [],
    "graphDefinition": {"startNodeId": "s", "nodes": {
        "s": {"type": "START", "transitions": [{"to": "v"}]},
        "v": {"type": "VALIDATION", "config": {"validatorType": "loadtest-fake"}, "transitions": [{"to": "e"}]},
        "e": {"type": "END"}}}})
assert s == 201, (s, b)
s, b = call(c, "PATCH", "/api/v1/flows/%s/activate" % json.loads(b)["id"])
assert s == 200, (s, b)

body = {"flowKey": flow_key, "userType": "PF", "context": "LOAD", "inputData": {"k": "v"}}
lat, errs, lock = [], {}, threading.Lock()
t0 = time.monotonic(); measure_from = t0 + warm; end = measure_from + dur


def worker():
    conn = http.client.HTTPConnection(u.hostname, u.port)
    while time.monotonic() < end:
        t = time.monotonic()
        try:
            st, _ = call(conn, "POST", "/api/v1/executions", body)
        except Exception as e:
            st = type(e).__name__
            conn = http.client.HTTPConnection(u.hostname, u.port)
        d = time.monotonic() - t
        if t >= measure_from:
            with lock:
                if st == 201:
                    lat.append(d)
                else:
                    errs[st] = errs.get(st, 0) + 1


ts = [threading.Thread(target=worker) for _ in range(conc)]
[t.start() for t in ts]; [t.join() for t in ts]
lat.sort()
pct = lambda p: lat[min(len(lat) - 1, int(p * len(lat)))] * 1000 if lat else float("nan")
print(json.dumps({"concurrency": conc, "duration_s": dur, "ok": len(lat), "errors": errs,
                  "throughput_rps": round(len(lat) / dur, 1),
                  "p50_ms": round(pct(.5), 1), "p95_ms": round(pct(.95), 1), "p99_ms": round(pct(.99), 1),
                  "max_ms": round(lat[-1] * 1000, 1) if lat else None}))
