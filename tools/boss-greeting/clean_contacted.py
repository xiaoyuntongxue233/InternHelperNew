# -*- coding: utf-8 -*-
"""把日志里出现过的公司（发过或失败过）从队列里剔除，避免每轮重复扫描"""
import json, os
BASE = os.path.dirname(os.path.abspath(__file__))
log = os.path.join(BASE, 'out', 'send_log.jsonl')
seen = set()
if os.path.exists(log):
    for line in open(log, encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
            n = str(r.get('company') or '').replace('...', '').replace('…', '').strip()
            if n:
                seen.add(n)
        except Exception:
            pass

for fn in ['queue_fresh.json', 'queue_gd.json', 'queue_sz.json']:
    p = os.path.join(BASE, fn)
    if not os.path.exists(p):
        continue
    d = json.load(open(p, encoding='utf-8'))
    kept = [j for j in d['jobs'] if str(j.get('company', '')).replace('...', '').replace('…', '').strip() not in seen]
    json.dump({'count': len(kept), 'jobs': kept}, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('%s: %d → %d' % (fn, len(d['jobs']), len(kept)))
