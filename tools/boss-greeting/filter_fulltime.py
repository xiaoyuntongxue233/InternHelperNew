# -*- coding: utf-8 -*-
"""过滤社招/校招类岗位（不是实习的）"""
import json, os, re
BASE = os.path.dirname(os.path.abspath(__file__))
p = os.path.join(BASE, 'queue_sz.json')
d = json.load(open(p, encoding='utf-8'))
TITLE_BAD = re.compile(r'校招|社招|应届|无经验|全职|正式员工|管培')
SAL_BAD = re.compile(r'\d{2,}-\d{2,}K')

kept, dropped = [], []
for j in d['jobs']:
    t = j.get('title', '')
    s = j.get('salary', '')
    if TITLE_BAD.search(t) or SAL_BAD.search(s):
        dropped.append('  去掉：%s / %s / %s' % (j['company'], t[:26], s))
        continue
    kept.append(j)

json.dump({'count': len(kept), 'jobs': kept}, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('保留 %d｜去掉 %d' % (len(kept), len(dropped)))
for x in dropped[:12]:
    print(x)
