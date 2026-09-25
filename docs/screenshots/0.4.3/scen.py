#!/usr/bin/env python3
"""write state.json: scen.py <phase>. Drone DEMO-7 at a synthetic desert point; aircraft placed by bearing/distance."""
import json, math, sys
S = "state.json"   # served by mock.py (python3 mock.py state.json)
LAT, LON = 39.5000, -118.2000   # synthetic, empty Nevada desert
DRONE = {"id": "ds-demo-0001", "callSign": "DEMO-7", "serial": "1581DEMO000000000007", "model": "M30T",
         "latitude": LAT, "longitude": LON, "altitudeMsl": 1400.0, "altitudeAgl": 100.0, "speed": 0.0}
def at(brg, nm):
    d = nm / 60.0
    return LAT + d*math.cos(math.radians(brg)), LON + d*math.sin(math.radians(brg))/math.cos(math.radians(LAT))
def ac(brg, nm, alt=5000, gs=0.0, trk=0.0, hexid="a00001", flight="DEMO22", t="C172", vs=0):
    la, lo = at(brg, nm)
    return {"hex": hexid, "flight": flight, "t": t, "lat": la, "lon": lo, "alt_baro": alt, "gs": gs, "track": trk, "baro_rate": vs}
# TFR: box east of the drone from 1.5 nm to 4 nm east, +-1.5 nm north/south (TFR 9/9999, surface to 8,000 MSL)
def box():
    pts = [at(90, 1.5), at(90, 4.0)]
    w0, w1 = pts[0][1], pts[1][1]; n = LAT + 1.5/60; s = LAT - 1.5/60
    ring = [[w0, s], [w1, s], [w1, n], [w0, n], [w0, s]]
    return {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"NOTAM_NUMBER": "9/9999",
        "_ALT_L_VAL": 0, "_ALT_L_UOM": "FT", "_ALT_L_CODE": "HEI", "_ALT_H_VAL": 8000, "_ALT_H_UOM": "FT", "_ALT_H_CODE": "ALT"},
        "geometry": {"type": "Polygon", "coordinates": [ring]}}]}
ph = sys.argv[1]
st = {"status": 200, "drones": [DRONE], "ac": [], "tfrs": box()}
if ph == "nodrone": st["drones"] = []
elif ph == "far": st["ac"] = [ac(225, 6.0)]
elif ph == "advisory": st["ac"] = [ac(225, 2.5)]
elif ph == "caution": st["ac"] = [ac(225, 0.8)]
elif ph == "zone_out": st["ac"] = [ac(80, 1.3, hexid="a00002", flight="DEMO33", t="R44")]   # west of the TFR edge (1.5 nm)
elif ph == "zone_in": st["ac"] = [ac(80, 1.8, hexid="a00002", flight="DEMO33", t="R44")]    # inside the TFR
elif ph == "empty": pass
json.dump(st, open(S + ".tmp", "w")); import os; os.replace(S + ".tmp", S)
print(ph)
