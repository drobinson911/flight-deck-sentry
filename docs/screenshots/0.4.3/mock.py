#!/usr/bin/env python3
"""Fully synthetic mock worker for Sentry screenshots. Never proxies anywhere."""
import json, time, math, sys
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
STATE = sys.argv[1]
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        try: st = json.load(open(STATE))
        except Exception: st = {}
        now = time.time(); p = self.path.split("?")[0]
        if p == "/api/live/our-drones":
            return self.send(200, {"ts": int(now*1000), "total": 0, "drones": []})
        if p == "/api/live/dronesense":
            body = [dict(d, lastUpdate=now) for d in st.get("drones", [])]
            return self.send(st.get("status", 200), {"ts": int(now*1000), "body": body})
        if p == "/api/live/adsb":
            ac = [dict(a, seen_pos=0.5, seen=0.5) for a in st.get("ac", [])]
            return self.send(200, {"now": now, "ac": ac})
        if p == "/api/tfrs":
            return self.send(200, st.get("tfrs", {"type": "FeatureCollection", "features": []}))
        return self.send(404, {"error": "mock: not served"})
ThreadingHTTPServer(("127.0.0.1", 18081), H).serve_forever()
