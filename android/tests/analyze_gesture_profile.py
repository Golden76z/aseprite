#!/usr/bin/env python3
"""Summarize Debug gesture CSVs; durations are nested, never sum all stages."""
import argparse
import collections
import csv
import json
import statistics
from pathlib import Path


def stats(values):
    values = sorted(values)
    if not values:
        return None
    return dict(n=len(values), avg_ms=statistics.mean(values)/1e6,
                p95_ms=values[min(len(values)-1, int((len(values)-1)*.95))]/1e6,
                max_ms=max(values)/1e6)


def analyze(path):
    with path.open() as f:
        rows = [{k: v if k == 'stage' else int(v) for k, v in r.items()} for r in csv.DictReader(f)]
    rows.sort(key=lambda r: r['t_ns'])
    by_stage = collections.defaultdict(list)
    for r in rows:
        by_stage[r['stage']].append(r)
    stages = {s: stats([r['duration_ns'] for r in rs]) for s, rs in by_stage.items()
              if any(r['duration_ns'] for r in rs)}
    motions = by_stage['motion']
    navs = {r['id']: r for r in by_stage['navigation']}
    queued = {r['id']: r for r in by_stage['queued']}
    callbacks = {r['id']: r for r in by_stage['gui_callback']}
    editors = {r['id']: r for r in by_stage['editor']}
    received = {r['id']: r for r in motions}
    updates = [r for r in by_stage['navigation'] if r['x0'] == 1]
    frames = []
    for frame in sorted({r['frame'] for r in by_stage['presentation']}):
        presentations = [r for r in by_stage['presentation'] if r['frame'] == frame]
        present = presentations[0]
        last_present = presentations[-1]
        events = [r for r in by_stage['editor'] if r['frame'] == frame and r['x0'] == 1]
        item = dict(frame=frame, timestamp_ns=present['t_ns'], post_ns=last_present['t_ns']+last_present['duration_ns'],
                    presentations=len(presentations),
                    framebuffer=[present['x0'], present['x1']], surface=[present['x2'], present['x3']],
                    gesture_begin=any(r['frame'] == frame and r['x0'] == 0 for r in by_stage['editor']),
                    updates=len(events), zoom_changed=any(r['x1'] for r in events),
                    pan_only=bool(events) and not any(r['x1'] for r in events) and any(r['x2'] for r in events))
        for stage in ['presentation', 'window_lock', 'raster_copy', 'window_post', 'gui_cycle',
                      'editor', 'editor_paint', 'redraw_invalidation', 'redraw_paint', 'sprite_render', 'skia_composite', 'skia_draw_image']:
            item[stage+'_ms'] = sum(r['duration_ns'] for r in by_stage[stage] if r['frame'] == frame)/1e6
        copies = [r for r in by_stage['raster_copy'] if r['frame'] == frame]
        item['copied_pixels'] = sum(r['x4'] for r in copies)
        item['copied_bytes'] = sum(r['x5'] for r in copies)
        frames.append(item)
    post = [f['post_ns'] for f in frames]
    intervals = [b-a for a, b in zip(post, post[1:])]
    moves = [r for r in motions if r['x2'] == 2 and r['x4'] == 2]
    input_tids = {r['tid'] for r in motions}
    wait = [r['duration_ns'] for r in by_stage['native_mutex_wait'] if r['tid'] in input_tids]
    # Native event time/receive cadence includes Android batching and app-side
    # input thread blocking; historical sample timestamps bound sensor cadence.
    gesture_begin = next((r['t_ns'] for r in by_stage['navigation'] if r['x0']==0), None)
    gesture_end = next((r['t_ns'] for r in by_stage['navigation'] if r['x0'] in (2,3)), None)
    gesture_seconds = (gesture_end-gesture_begin)/1e9 if gesture_begin and gesture_end else None
    incomplete_reasons = []
    if len(rows) >= 16384:
        incomplete_reasons.append('record_capacity_reached_check_overflow_log')
    if gesture_begin is None or gesture_end is None:
        incomplete_reasons.append('missing_navigation_begin_or_end')
    summary = dict(file=path.name, records=len(rows), frames=len(frames), presentations=len(by_stage['presentation']),
        complete_gesture=not incomplete_reasons, incomplete_reasons=incomplete_reasons,
        devices=sorted({r['x3'] for r in motions}),
        gesture_seconds=gesture_seconds,
        presented_frames_per_gesture_second=len(frames)/gesture_seconds if gesture_seconds else None,
        zoom_changing_updates=sum(r['x1'] for r in editors.values()),
        motion_moves=len(moves), historical_samples=sum(r['x1'] for r in moves),
        historical_max=max([r['x1'] for r in moves], default=0),
        navigation_updates=len(updates), queued=len(queued), callbacks=len(callbacks), editor_events=len(editors),
        queued_without_callback=sorted(queued.keys()-callbacks.keys()),
        callbacks_without_editor=sorted(callbacks.keys()-editors.keys()),
        visual_updates_combined=sum(max(0,f['updates']-1) for f in frames),
        max_updates_per_frame=max([f['updates'] for f in frames], default=0),
        stages=stages, frame_interval=stats(intervals),
        fps=(len(post)-1)*1e9/(post[-1]-post[0]) if len(post)>1 and post[-1]>post[0] else None,
        input_receive_interval=stats([b['t_ns']-a['t_ns'] for a,b in zip(moves,moves[1:])]),
        native_current_sample_interval=stats([b['x0']-a['x0'] for a,b in zip(moves,moves[1:])]),
        input_delivery_age=stats([r['t_ns']-r['x0'] for r in moves]),
        input_native_mutex_wait=stats(wait),
        receive_to_queue=stats([queued[i]['t_ns']-received[i]['t_ns'] for i in queued if i in received]),
        queue_to_callback=stats([callbacks[i]['t_ns']-queued[i]['t_ns'] for i in queued if i in callbacks]),
        zoom_frame=stats([int(f['gui_cycle_ms']*1e6) for f in frames if f['zoom_changed']]),
        pan_frame=stats([int(f['gui_cycle_ms']*1e6) for f in frames if f['pan_only']]))
    presentation_rows = [dict(r, zoom_changed=next(f['zoom_changed'] for f in frames if f['frame']==r['frame']),
        pan_only=next(f['pan_only'] for f in frames if f['frame']==r['frame'])) for r in by_stage['presentation']]
    path.with_suffix('.presentations.json').write_text(json.dumps(presentation_rows,indent=2))
    with path.with_suffix('.frames.json').open('w') as f:
        json.dump(frames,f,indent=2)
    # Each update links to its GUI frame and that frame's presentation metadata.
    frame_map = {f['frame']: f for f in frames}
    update_rows = []
    for r in updates:
        i=r['id']; e=editors.get(i); callback=callbacks.get(i)
        update_rows.append(dict(id=i, receive_ns=received.get(i,{}).get('t_ns'),
            queued_ns=queued.get(i,{}).get('t_ns'), callback_ns=callback['t_ns'] if callback else None,
            editor_ns=e['t_ns'] if e else None, editor_duration_ns=e['duration_ns'] if e else None,
            zoom_changed=bool(e['x1']) if e else None, pan_only=bool(e['x2']) if e else None,
            presentation=frame_map.get(e['frame']) if e else None))
    path.with_suffix('.updates.json').write_text(json.dumps(update_rows,indent=2))
    path.with_suffix('.summary.json').write_text(json.dumps(summary,indent=2))
    return summary

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv',nargs='+',type=Path)
    args=parser.parse_args()
    for path in args.csv:
        print(json.dumps(analyze(path),indent=2))
