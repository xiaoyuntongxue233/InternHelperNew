# -*- coding: utf-8 -*-
import json, collections, os
BASE = os.path.dirname(os.path.abspath(__file__))
d = json.load(open(os.path.join(BASE, 'queue_fresh.json'), encoding='utf-8'))['jobs']
c = collections.Counter((j.get('city') or '').split()[0] for j in d)
lines = ['共 %d 个，城市分布：%s' % (len(d), '，'.join('%s %d' % (k, v) for k, v in c.most_common(8)))]
lines.append('')
for j in d[:12]:
    lines.append('%-16s | %-30s | %-10s | %d个月/%d天' % (
        j['company'][:16], j['title'][:30], (j.get('city') or '')[:10], j['months'], j['days']))
open(os.path.join(BASE, 'preview2.txt'), 'w', encoding='utf-8').write('\n'.join(lines))
print('ok')
