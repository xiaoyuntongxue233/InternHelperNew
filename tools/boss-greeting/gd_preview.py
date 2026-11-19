# -*- coding: utf-8 -*-
"""广东岗位预览"""
import json, os, collections
BASE = os.path.dirname(os.path.abspath(__file__))
d = json.load(open(os.path.join(BASE, 'queue_gd.json'), encoding='utf-8'))['jobs']
c = collections.Counter((j.get('city') or '').split()[0] for j in d)
lines = ['共 %d 个：%s' % (len(d), '，'.join('%s %d' % (k, v) for k, v in c.most_common(8))), '']
for j in d[:12]:
    lines.append('%-16s | %-28s | %-12s | %d个月/%d天' % (
        j['company'][:16], j['title'][:28], (j.get('city') or '')[:12], j['months'], j['days']))
open(os.path.join(BASE, 'gd_preview.txt'), 'w', encoding='utf-8').write('\n'.join(lines))
print('ok')
