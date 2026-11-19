# -*- coding: utf-8 -*-
"""
按统一模板，为「目前已有会话的各家」+「待联系的新岗位」生成打招呼语（只生成，不发送）。

固定框架（不改）：
您好，我是XX大学（XX）2028届本科生，本人{技能句}，现可熟悉使用AI agent相关软件。
本人可连续实习至少3个月，每周五天。希望收到您的回复。

只替换 {技能句}，依据岗位名称 / 岗位类别 / 技术栈关键词。
"""
import json, os, re
from collections import Counter
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BASE_DIR)

FIXED_HEAD = '您好，我是XX大学（XX）2028届本科生，本人'
FIXED_AI = '，现可熟悉使用AI agent相关软件。'
FIXED_TAIL_TPL = '本人可连续实习至少{months}个月，每周{days}天。希望收到您的回复。'
DEFAULT_MONTHS = 3
DEFAULT_DAYS = 5

VARIANTS = [
    (r'测试|测开|QA|质量', '熟悉Java，有接口测试、用例设计与缺陷跟踪相关经验', '测试方向'),
    (r'数据开发|大数据|数仓|ETL|BI|Hadoop|Spark|Flink', '熟悉Java与SQL，有MySQL、数据开发相关经验', '数据方向'),
    (r'算法|机器学习|深度学习|NLP|CV|推荐', '熟悉Java，有数据结构与算法、MySQL相关经验', '算法方向'),
    (r'\bAI\b|大模型|Agent|智能体|LLM|AIGC|人工智能', '熟悉Java，有Springboot、MySQL相关经验', 'AI 方向'),
    (r'Golang|GO开发|Go语言|\bGo\b', '熟悉Java与Golang，有Springboot、MySQL相关经验', 'Go 方向'),
    (r'前端|全栈|Vue|React', '熟悉Java，有Springboot、MySQL相关经验，了解前端基础', '全栈方向'),
    (r'SpringCloud|微服务|Dubbo|分布式|Nacos', '熟悉Java，有Springboot、SpringCloud、MySQL相关经验', '微服务方向'),
    (r'Redis|缓存|Kafka|RocketMQ|消息队列|MQ', '熟悉Java，有Springboot、MySQL、Redis相关经验', '中间件方向'),
    (r'Linux|运维|部署|Docker|K8s|容器', '熟悉Java，有Springboot、MySQL、Linux相关经验', '运维方向'),
]
DEFAULT_CLAUSE = '熟悉Java，有Springboot，MySQL相关经验'
DEFAULT_NOTE = '通用 Java 方向'

BAD_COMPANY = re.compile(r'BOSS直聘|未知|^$|测试公司|示例')


def pick(title, cat='', stack='', jd=''):
    blob = ' '.join([title or '', cat or '', stack or '', jd or ''])
    for pat, clause, note in VARIANTS:
        if re.search(pat, blob, re.I):
            return clause, note
    return DEFAULT_CLAUSE, DEFAULT_NOTE


def pick_schedule(title='', cat='', stack='', jd='', exp=''):
    """从岗位要求里解析「每周天数 / 实习月数」，解析不到就用默认值。"""
    blob = ' '.join([jd or '', exp or '', title or '', stack or ''])
    days = None
    months = None
    m = re.search(r'(\d+)\s*天\s*/\s*周', blob)
    if m:
        days = int(m.group(1))
    m = re.search(r'每周\s*(\d+)\s*天', blob)
    if m and not days:
        days = int(m.group(1))
    m = re.search(r'(\d+)\s*个月', blob)
    if m:
        months = int(m.group(1))
    m = re.search(r'至少\s*(\d+)\s*月', blob)
    if m and not months:
        months = int(m.group(1))

    days = days if days and 1 <= days <= 7 else DEFAULT_DAYS
    months = months if months and 1 <= months <= 12 else DEFAULT_MONTHS
    # 在校生现实上限：每周最多 5 天、连续最多 6 个月
    days = max(2, min(days, 5))
    months = max(2, min(months, 6))
    return months, days


def make(title, cat='', stack='', jd='', exp=''):
    clause, note = pick(title, cat, stack, jd)
    months, days = pick_schedule(title, cat, stack, jd, exp)
    tail = FIXED_TAIL_TPL.format(months=months, days=days)
    msg = FIXED_HEAD + clause + FIXED_AI + tail
    return msg, clause, note, months, days


def load_conversations():
    seen = {}

    def add(name, title='', city='', salary='', cat='', hr='', src='', exp=''):
        name = (name or '').strip()
        if not name or BAD_COMPANY.search(name) or name in seen:
            return
        seen[name] = dict(company=name, hr=hr, title=(title or '').strip(), city=(city or '').strip(),
                          salary=(salary or '').strip(), cat=(cat or '').strip(), stack='', jd='',
                          exp=(exp or '').strip(), src=src)

    p = os.path.join(ROOT, 'data', 'chat.json')
    if os.path.exists(p):
        for f in json.load(open(p, encoding='utf-8')).get('friends', []):
            job = f.get('job') or {}
            add(f.get('brandName'), (job.get('title') or '').strip('"'), (job.get('city') or '').strip('"'),
                (job.get('salary') or '').strip('"'), (job.get('cat') or '').strip('"'),
                f.get('name', ''), '11-02 已有会话', (job.get('exp') or '').strip('"'))

    p2 = os.path.join(BASE_DIR, 'out', 'send_log.jsonl')
    if os.path.exists(p2):
        for line in open(p2, encoding='utf-8'):
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except Exception:
                continue
            add(r.get('company'), r.get('title'), '', '', '', '', '本轮已发')

    p3 = os.path.join(BASE_DIR, 'send_plan_missing.json')
    if os.path.exists(p3):
        for it in json.load(open(p3, encoding='utf-8')):
            add(it.get('company'), '', '', '', '', '', '本轮补发')

    return sorted(seen.values(), key=lambda x: x['company'])


def load_new_jobs():
    out = []
    p = os.path.join(BASE_DIR, 'fresh_jobs.json')
    if os.path.exists(p):
        for j in json.load(open(p, encoding='utf-8')):
            name = (j.get('company') or '').strip()
            if not name or BAD_COMPANY.search(name):
                continue
            out.append(dict(company=name, hr='', title=j.get('title', ''), city=j.get('city', ''),
                            salary=j.get('salary', ''), cat='', stack=j.get('stack', ''), jd=j.get('jd', ''),
                            src='实时岗位'))
    return sorted(out, key=lambda x: x['company'])


def style_header(ws, n):
    for c in range(1, n + 1):
        cell = ws.cell(row=1, column=c)
        cell.font = Font(bold=True, color='FFFFFF')
        cell.fill = PatternFill('solid', fgColor='1F6F63')
        cell.alignment = Alignment(horizontal='center', vertical='center')


def fill_sheet(ws, rows, widths):
    ws.append(['序号', '公司', '岗位', '城市', '薪资', '技能句依据', '实习月数', '每周天数', '岗位原始要求', '字数', '打招呼语'])
    style_header(ws, 11)
    for i, r in enumerate(rows, 1):
        ws.append([i, r['company'], r['title'], r['city'], r['salary'], r['note'],
                   r['months'], r['days'], r.get('req', ''), r['len'], r['message']])
    for i, w in enumerate(widths, start=1):
        ws.column_dimensions[chr(64 + i)].width = w
    for row in ws.iter_rows(min_row=2):
        row[10].alignment = Alignment(wrap_text=True, vertical='top')


conv = []
for r in load_conversations():
    msg, clause, note, months, days = make(r['title'], r['cat'], r['stack'], r['jd'], r.get('exp', ''))
    conv.append({**r, 'clause': clause, 'note': note, 'message': msg, 'len': len(msg),
                 'months': months, 'days': days, 'req': r.get('exp', '')})

new = []
seen_new = {c['company'] for c in conv}
for r in load_new_jobs():
    if r['company'] in seen_new:
        continue
    msg, clause, note, months, days = make(r['title'], r['cat'], r['stack'], r['jd'], r.get('exp', ''))
    new.append({**r, 'clause': clause, 'note': note, 'message': msg, 'len': len(msg),
                'months': months, 'days': days, 'req': r.get('jd', '')})

wb = Workbook()
ws1 = wb.active
ws1.title = '已有会话'
fill_sheet(ws1, conv, [6, 24, 30, 18, 14, 14, 10, 10, 16, 6, 80])

ws2 = wb.create_sheet('新岗位(未联系)')
fill_sheet(ws2, new, [6, 24, 30, 18, 14, 14, 10, 10, 16, 6, 80])

ws3 = wb.create_sheet('模板说明')
ws3.append(['项目', '内容'])
ws3.append(['固定框架', FIXED_HEAD + '{技能句}' + FIXED_AI + FIXED_TAIL_TPL.format(months='{月数}', days='{天数}')])
ws3.append(['可变部分①', '{技能句}：按岗位名称/类别/技术栈关键词匹配'])
ws3.append(['可变部分②', '{月数}/{天数}：从岗位要求（如「5天/周、6个月」）解析，解析不到默认 3 个月 / 5 天'])
for pat, clause, note in VARIANTS:
    ws3.append([note, clause])
ws3.append(['默认', DEFAULT_CLAUSE])
ws3.append(['说明', '老岗位多已下线，拿不到 JD 里的实习要求；这些行按默认 3 个月 / 5 天'])
ws3.column_dimensions['A'].width = 16
ws3.column_dimensions['B'].width = 110

out = os.path.join(BASE_DIR, 'out_review', '打招呼语_统一模板.xlsx')
os.makedirs(os.path.dirname(out), exist_ok=True)
wb.save(out)

json.dump({'conversations': conv, 'new_jobs': new},
          open(os.path.join(BASE_DIR, 'out_review', '打招呼语_统一模板.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=1)

print('已有会话 %d 家｜新岗位 %d 家' % (len(conv), len(new)))
print('会话方向分布:', dict(Counter(r['note'] for r in conv)))
print('新岗位方向分布:', dict(Counter(r['note'] for r in new)))
print('会话时长分布:', dict(Counter((r['months'], r['days']) for r in conv)))
print('新岗位时长分布:', dict(Counter((r['months'], r['days']) for r in new)))
print('保存：' + out)
