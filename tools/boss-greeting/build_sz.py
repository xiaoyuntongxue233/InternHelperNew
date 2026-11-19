# -*- coding: utf-8 -*-
"""深圳岗位 → 打招呼语句（只生成，不发送）"""
import json, os, re, collections

BASE = os.path.dirname(os.path.abspath(__file__))
FIXED_HEAD = '您好，我是XX大学（XX）2028届本科生，本人'
FIXED_AI = '，现可熟悉使用AI agent相关软件。'
FIXED_TAIL = '本人可连续实习至少{months}个月，每周{days}天。希望收到您的回复。'

VARIANTS = [
    (r'测试|测开|QA|质量', '熟悉Java，有接口测试、用例设计与缺陷跟踪相关经验'),
    (r'数据开发|大数据|数仓|ETL|BI|Hadoop|Spark|Flink', '熟悉Java与SQL，有MySQL、数据开发相关经验'),
    (r'算法|机器学习|深度学习|NLP|CV|推荐', '熟悉Java，有数据结构与算法、MySQL相关经验'),
    (r'\bAI\b|大模型|Agent|智能体|LLM|AIGC|人工智能', '熟悉Java，有Springboot、MySQL相关经验'),
    (r'Golang|GO开发|Go语言|\bGo\b', '熟悉Java与Golang，有Springboot、MySQL相关经验'),
    (r'前端|全栈|Vue|React', '熟悉Java，有Springboot、MySQL相关经验，了解前端基础'),
    (r'SpringCloud|微服务|Dubbo|分布式|Nacos', '熟悉Java，有Springboot、SpringCloud、MySQL相关经验'),
    (r'Redis|缓存|Kafka|RocketMQ|消息队列|MQ', '熟悉Java，有Springboot、MySQL、Redis相关经验'),
    (r'Linux|运维|部署|Docker|K8s|容器', '熟悉Java，有Springboot、MySQL、Linux相关经验'),
]
DEFAULT = '熟悉Java，有Springboot，MySQL相关经验'
OFFTOPIC = re.compile(r'货车|司机|销售|客服|主播|带货|运营(?!开发)|文员|前台|催收|保险|房产|中介')


def clause_of(t):
    for pat, c in VARIANTS:
        if re.search(pat, t, re.I):
            return c
    return DEFAULT


def schedule_of(t):
    days = months = None
    m = re.search(r'(\d+)\s*天\s*/\s*周', t)
    if m:
        days = int(m.group(1))
    m = re.search(r'(\d+)\s*个月', t)
    if m:
        months = int(m.group(1))
    days = days if days and 1 <= days <= 7 else 5
    months = months if months and 1 <= months <= 12 else 3
    return max(2, min(months, 6)), max(2, min(days, 5))


src = json.load(open(os.path.join(BASE, 'fresh_jobs.json'), encoding='utf-8'))
jobs, seen = [], set()
for j in src:
    name = (j.get('company') or '').strip()
    title = (j.get('title') or '')
    url = (j.get('url') or '').strip()
    if not name or not url or name in seen or OFFTOPIC.search(title):
        continue
    seen.add(name)
    blob = ' '.join([title, j.get('stack', ''), j.get('jd', '')])
    months, days = schedule_of(blob)
    msg = FIXED_HEAD + clause_of(blob) + FIXED_AI + FIXED_TAIL.format(months=months, days=days)
    jobs.append({'company': name, 'title': title, 'city': j.get('city', ''), 'salary': j.get('salary', ''),
                 'url': url, 'message': msg, 'months': months, 'days': days})

json.dump({'count': len(jobs), 'jobs': jobs}, open(os.path.join(BASE, 'queue_sz.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)

lines = ['共 %d 个：%s' % (len(jobs), '，'.join('%s %d' % (k, v) for k, v in
         collections.Counter((x['city'] or '').split()[0] for x in jobs).most_common(6))), '']
for i, x in enumerate(jobs[:10], 1):
    lines.append('%d. %s | %s | %s | %s' % (i, x['company'], x['title'][:26], x['city'][:12], x['salary']))
    lines.append('   ' + x['message'])
open(os.path.join(BASE, 'sz_preview.txt'), 'w', encoding='utf-8').write('\n'.join(lines))
print('生成', len(jobs), '条')
