# -*- coding: utf-8 -*-
"""把新抓的 fresh_jobs.json 合并进 queue_fresh.json（按 url 去重，新岗位按模板生成话术）"""
import json, os, re

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
BAD = re.compile(r'BOSS直聘|未知|^$')
# 明显不是技术岗的标题，直接丢弃
OFFTOPIC = re.compile(r'货车|司机|销售|客服|主播|运营|文员|助理(?!工程师)|实习会计|招聘')


def clause_of(text):
    for pat, c in VARIANTS:
        if re.search(pat, text, re.I):
            return c
    return DEFAULT


def schedule_of(text):
    days = months = None
    m = re.search(r'(\d+)\s*天\s*/\s*周', text)
    if m:
        days = int(m.group(1))
    m = re.search(r'(\d+)\s*个月', text)
    if m:
        months = int(m.group(1))
    days = days if days and 1 <= days <= 7 else 5
    months = months if months and 1 <= months <= 12 else 3
    return max(2, min(months, 6)), max(2, min(days, 5))


def make(j):
    blob = ' '.join([j.get('title', ''), j.get('stack', ''), j.get('jd', '')])
    months, days = schedule_of(blob)
    return (FIXED_HEAD + clause_of(blob) + FIXED_AI +
            FIXED_TAIL.format(months=months, days=days)), months, days


old = json.load(open(os.path.join(BASE, 'queue_fresh.json'), encoding='utf-8'))['jobs'] if os.path.exists(os.path.join(BASE, 'queue_fresh.json')) else []
seen = {(j.get('url') or '') for j in old}
seen_companies = {(j.get('company') or '') for j in old}
added = []
for j in json.load(open(os.path.join(BASE, 'fresh_jobs.json'), encoding='utf-8')):
    name = (j.get('company') or '').strip()
    url = (j.get('url') or '').strip()
    title = (j.get('title') or '')
    if not name or not url or BAD.search(name) or url in seen or name in seen_companies:
        continue
    if OFFTOPIC.search(title):
        continue
    msg, months, days = make(j)
    added.append({'company': name, 'title': title, 'city': j.get('city', ''),
                  'url': url, 'message': msg, 'months': months, 'days': days})
    seen.add(url)
    seen_companies.add(name)

alljobs = old + added
json.dump({'count': len(alljobs), 'jobs': alljobs},
          open(os.path.join(BASE, 'queue_fresh.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('原有 %d｜新增 %d｜合计 %d' % (len(old), len(added), len(alljobs)))
