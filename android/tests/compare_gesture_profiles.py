#!/usr/bin/env python3
"""Compare matching bounded gesture traces, with Begin separate from updates."""
import argparse
import json
import statistics
from pathlib import Path
from analyze_gesture_profile import analyze


def measure(values):
    return dict(n=len(values), avg=statistics.mean(values), worst=max(values)) if values else None


def summarize(paths):
    traces = []
    frames = []
    for path in paths:
        summary = analyze(path)
        if not summary['complete_gesture']:
            raise ValueError(f'Incomplete capture: {path}: {summary["incomplete_reasons"]}')
        if summary['queued_without_callback'] or summary['callbacks_without_editor']:
            raise ValueError(f'Incomplete dispatch: {path}')
        traces.append(summary)
        frames.extend(json.loads(path.with_suffix('.frames.json').read_text()))
    begin = [f for f in frames if f['gesture_begin']]
    pan = [f for f in frames if f['pan_only'] and not f['gesture_begin']]
    zoom = [f for f in frames if f['zoom_changed'] and not f['gesture_begin']]
    stages = ['gui_cycle', 'skia_composite', 'editor_paint', 'redraw_paint',
              'sprite_render', 'raster_copy', 'window_lock', 'window_post', 'presentation']
    result = dict(files=[p.name for p in paths],
                  framebuffer=sorted({tuple(f['framebuffer']) for f in frames}),
                  surface=sorted({tuple(f['surface']) for f in frames}),
                  posts_per_frame=sorted({f['presentations'] for f in frames}),
                  begin={s: measure([f[s+'_ms'] for f in begin]) for s in stages},
                  pan={s: measure([f[s+'_ms'] for f in pan]) for s in stages},
                  zoom={s: measure([f[s+'_ms'] for f in zoom]) for s in stages})
    # Each CSV has its own ID/thread namespace: summarize latency per trace,
    # weighting by sample count rather than averaging per-trace averages.
    for name in ['queue_to_callback', 'input_native_mutex_wait', 'frame_interval',
                 'input_delivery_age', 'input_receive_interval']:
        parts = [s[name] for s in traces if s[name]]
        n = sum(p['n'] for p in parts)
        result[name] = dict(n=n, avg=sum(p['avg_ms']*p['n'] for p in parts)/n,
                            worst=max(p['max_ms'] for p in parts)) if n else None
    result['gestures'] = [{k: t[k] for k in ['file', 'frames', 'fps', 'gesture_seconds',
        'navigation_updates', 'visual_updates_combined', 'max_updates_per_frame',
        'historical_samples', 'devices']} for t in traces]
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', nargs='+', type=Path, required=True)
    parser.add_argument('--candidate', nargs='+', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = dict(baseline=summarize(args.baseline), candidate=summarize(args.candidate))
    for key in ['framebuffer', 'surface', 'posts_per_frame']:
        if result['baseline'][key] != result['candidate'][key]:
            raise ValueError(f'Unmatched {key}')
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(args.output)
