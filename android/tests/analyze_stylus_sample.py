#!/usr/bin/env python3
"""Summarize a bounded AsepriteStylus log; never classify empty logs as validation."""
import argparse
import json
import math
import re
from pathlib import Path


def analyze(path, token):
    rows = []
    for line in path.read_text().splitlines():
        if 'AsepriteStylus:' not in line:
            continue
        pairs = dict(re.findall(r'(\w+)=([^\s]+)', line))
        if pairs.get('token') != token:
            continue
        ints = ('sample', 'timeNs', 'eventNs', 'device', 'source', 'action', 'tool', 'buttons', 'history')
        floats = ('x', 'y', 'pressure', 'tilt', 'orientation')
        rows.append({**{k: int(pairs[k], 0) for k in ints},
                     **{k: float(pairs[k]) for k in floats}})
    if not rows:
        raise ValueError(f'No stylus samples for {token}; physical validation remains pending')
    def extent(key, selected):
        values = [r[key] for r in selected if math.isfinite(r[key])]
        return dict(min=min(values), max=max(values)) if values else None
    result = dict(token=token, records=len(rows),
                  devices=sorted({r['device'] for r in rows}),
                  tools=sorted({r['tool'] for r in rows}),
                  sources=[hex(s) for s in sorted({r['source'] for r in rows})],
                  buttons=[hex(b) for b in sorted({r['buttons'] for r in rows})],
                  actions={a: sum(r['action'] == a for r in rows) for a in sorted({r['action'] for r in rows})},
                  duration_seconds=(rows[-1]['timeNs']-rows[0]['timeNs'])/1e9,
                  physical_device_seen=any(r['device'] >= 0 for r in rows),
                  note='Sampled MOVE/hover at <=10 Hz; ranges are observed values, not calibration or full hardware limits.')
    for key in ('pressure', 'tilt', 'orientation'):
        result[key] = extent(key, rows)
    result['contact_pressure'] = extent('pressure', [r for r in rows if r['action'] in (0, 2)])
    result['up_pressure'] = extent('pressure', [r for r in rows if r['action'] == 1])
    for key in ('tilt', 'orientation'):
        result[key+'_degrees'] = {k: math.degrees(v) for k, v in result[key].items()} if result[key] else None
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log', type=Path)
    parser.add_argument('--token', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = analyze(args.log, args.token)
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
