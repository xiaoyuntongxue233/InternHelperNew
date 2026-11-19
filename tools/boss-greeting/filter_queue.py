# -*- coding: utf-8 -*-
"""从 queue_fresh.json 里筛掉不投的城市（西藏、青海等），输出 queue_next.json"""
import json, os, re

BASE = os.path.dirname(os.path.abspath(__file__))
BLOCK = re.compile(r'拉萨|西藏|青海|西宁|日喀则|林芝|昌都|山南|那曲|阿里|海东|海西|格尔木|玉树|果洛|黄南|海北|海南州')
# 备注：只排除用户明确要求的地域；其他城市照常

src = json.load(open(os.path.join(BASE, 'queue_fresh.json'), encoding='utf-8'))
kept, dropped = [], []
for j in src['jobs']:
    blob = (j.get('city', '') or '') + ' ' + (j.get('title', '') or '')
    if BLOCK.search(blob):
        dropped.append(f"{j['company']} / {j['title'][:24]} / {j.get('city','')}")
        continue
    kept.append(j)

json.dump({'count': len(kept), 'jobs': kept},
          open(os.path.join(BASE, 'queue_next.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('保留', len(kept), '个｜排除', len(dropped), '个')
for d in dropped:
    print('  排除：', d)
