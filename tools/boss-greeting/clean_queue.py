# -*- coding: utf-8 -*-
"""清理队列：去掉明显非技术岗"""
import json, os, re
BASE = os.path.dirname(os.path.abspath(__file__))
OFFTOPIC = re.compile(r'货车|司机|销售|客服|主播|带货|运营(?!开发)|文员|前台|催收|保险|房产|中介')
p = os.path.join(BASE, 'queue_fresh.json')
d = json.load(open(p, encoding='utf-8'))
kept, dropped = [], []
for j in d['jobs']:
    if OFFTOPIC.search(j.get('title', '')):
        dropped.append('  去掉：%s / %s' % (j['company'], j['title'][:24]))
        continue
    kept.append(j)
json.dump({'count': len(kept), 'jobs': kept}, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('保留 %d｜去掉 %d' % (len(kept), len(dropped)))
for x in dropped[:8]:
    print(x)
