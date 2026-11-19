# -*- coding: utf-8 -*-
"""
BOSS 直聘打招呼语生成器（v3）

用法：
  python greeting_gen.py --input "..\\实习岗位清单_BOSS直聘.xlsx" --sheet 实习岗位
  python greeting_gen.py --input jobs.json --out-dir out

输入：
  * .xlsx  表头需含 名称 / 技术栈 / 公司 / 地点（可选 要求、薪资、职位链接）
  * .json  [{"title","company","city","stack","jd","url"}]

输出（默认 ./out）：
  greetings.xlsx  人工复核表（匹配度、关键词、可发送标记）
  queue.json      给 send_boss.mjs 的待发送队列
"""

import argparse
import json
import os
import random
import re
import sys
from collections import Counter

# ---------------------------------------------------------------- 词库

TECH_LEXICON = {
    'Java': ['java', 'jdk', 'j2ee'],
    'SpringBoot': ['springboot', 'spring boot', 'spring'],
    'SpringCloud': ['springcloud', 'spring cloud', '微服务'],
    'MyBatis': ['mybatis', 'mybatis-plus'],
    'MySQL': ['mysql'],
    'Redis': ['redis'],
    'Kafka': ['kafka', 'rocketmq', '消息队列'],
    'Docker': ['docker', 'k8s', 'kubernetes'],
    'Linux': ['linux', 'shell'],
    'Git': ['git'],
    '并发': ['并发', '多线程', '线程池', 'juc'],
    'JVM': ['jvm', '垃圾回收'],
    '分布式': ['分布式', 'rpc', 'dubbo', 'nacos'],
    'Golang': ['golang', 'go 语言', 'go语言'],
    'Python': ['python', 'django', 'flask'],
    'C++': ['c++', 'cpp'],
    '算法': ['算法', '数据结构', 'leetcode'],
    '计算机网络': ['tcp', 'http', '网络协议'],
    '操作系统': ['操作系统', '内存管理', '进程调度'],
    '测试': ['测试用例', '自动化测试', 'selenium', 'pytest', '接口测试'],
    '大数据': ['hadoop', 'spark', 'flink', 'hive', '数仓'],
    'AI': ['大模型', 'llm', 'agent', 'aigc', '机器学习'],
    '前端': ['vue', 'react', 'javascript'],
}

MY_SKILLS = {'Java', 'SpringBoot', 'MySQL', '算法', '并发', 'Git', 'Linux',
             'SpringCloud', 'MyBatis', '分布式', '操作系统', '计算机网络'}

DIRECTIONS = [
    ('测试开发', ['测试', '测开', 'qa']),
    ('数据开发', ['数据开发', '数仓', '大数据', 'etl']),
    ('Golang', ['golang', 'go开发', 'go 开发']),
    ('前端', ['前端', 'vue', 'react']),
    ('算法', ['算法工程', '机器学习', '推荐算法', 'nlp', 'cv']),
    ('Java后端', ['java', '后端', '服务端']),
]

BLACKLIST = ['主播', '电销', '日结', '招生', '中介', '贷款', '保险', '地推',
             '娱乐', '带货', '实习证明', '兼职', '销售']

# 证据句：只写简历站得住的，控制在 30 字内
PROOF = {
    'Java': '独立写过 RBAC 权限 + 分层架构的 Java 项目，社团在用。',
    'SpringBoot': 'SpringBoot 写过完整 REST 接口，含统一异常处理。',
    'MySQL': 'MySQL 建表、索引和常用 SQL 都自己写。',
    '算法': '链表、队列、归并排序都是手写，复杂度能讲清。',
    '并发': '多线程和线程池学过，能写基础并发场景。',
    '测试': '接口和边界用例写过，异常路径能覆盖到。',
    'Python': 'Python 能写脚本和数据处理。',
    'C++': 'C++ 写过课程项目。',
    'Golang': 'Go 的语法和并发模型能快速上手。',
}
DEFAULT_PROOF = '独立做过完整 Java 项目，从建模到测试自己走一遍。'

CTAS = ['简历可以马上发您。', '方便的话想先投份简历。', '简历我随时能发。']


def norm(s):
    return (s or '').strip()


def clean_title(t, limit=22):
    t = norm(t).replace('...', '').replace('…', '')
    t = re.sub(r'[（(][^）)]{6,}[）)]', '', t)
    t = re.sub(r'(急招|急聘|长期招聘|高薪|包住|可转正|双休|包吃住)', '', t)
    t = t.replace('•', ' ').replace('·', ' ')
    t = re.sub(r'\s+', ' ', t).strip(' -/')
    # 技术名词大小写统一
    fixes = {'java': 'Java', 'golang': 'Golang', 'springboot': 'SpringBoot',
             'spring boot': 'SpringBoot', 'mysql': 'MySQL', 'redis': 'Redis',
             'python': 'Python', 'mybatis': 'MyBatis', 'c++': 'C++'}
    for k, v in fixes.items():
        t = re.sub(r'(?<![A-Za-z])' + re.escape(k) + r'(?![A-Za-z])', v, t)
    return t[:limit]


def clean_company(c):
    return norm(c).replace('...', '').replace('…', '')[:18]


def detect_direction(title, text):
    t = (title or '').lower()
    blob = t + ' ' + (text or '').lower()
    for name, keys in DIRECTIONS:
        if any(k in t for k in keys):
            return name
    for name, keys in DIRECTIONS:
        if any(k in blob for k in keys):
            return name
    return '其他'


def extract_keywords(text):
    low = (text or '').lower()
    score = Counter()
    for canon, variants in TECH_LEXICON.items():
        for v in variants:
            if v in low:
                score[canon] += low.count(v)
                break
    return score


def pick_keywords(score):
    if not score:
        return []
    matched = [k for k in score if k in MY_SKILLS]
    pool = matched or list(score)
    return sorted(pool, key=lambda k: -score[k])[:2]


def pick_proof(kws):
    for k in kws:
        if k in PROOF:
            return PROOF[k]
    return DEFAULT_PROOF


def is_blacklisted(title, company):
    blob = (title or '') + (company or '')
    return [w for w in BLACKLIST if w in blob]


def build_greeting(job, profile, rng, used):
    title = clean_title(job.get('title'))
    company = clean_company(job.get('company'))
    text = ' '.join([norm(job.get('stack')), norm(job.get('jd')), norm(job.get('title'))])
    direction = detect_direction(job.get('title') or '', text)
    kws = pick_keywords(extract_keywords(text))
    proof = pick_proof(kws)

    school = profile['school']
    grade = profile['grade']
    major = profile['major']
    name = profile['name']
    av = profile['availability']
    if not av.endswith('。'):
        av += '。'
    cta = rng.choice(CTAS)

    skeletons = [
        '您好，我是{school} {grade}{major}的{name}。看到贵司在招{title}，{proof}{av}{cta}',
        '您好，我是{name}，{school} {grade}{major}。想投{title}：{proof}{av}{cta}',
        '{title}这个岗我想投。{proof}我是{school} {grade}{major}的{name}，{av}',
        '您好，{school} {grade}{major}的{name}，想投{title}。{proof}{av}',
    ]
    rng.shuffle(skeletons)
    max_len = profile.get('max_len', 110)

    for t_limit in (22, 16, 12):
        t = clean_title(job.get('title'), t_limit)
        for sk in skeletons:
            msg = sk.format(school=school, grade=grade, major=major, name=name,
                            title=t or '这个岗位', proof=proof, av=av, cta=cta)
            msg = re.sub(r'\s+', ' ', msg).strip()
            msg = msg.replace('。。', '。')
            if len(msg) <= max_len and msg not in used:
                used.add(msg)
                return _rec(job, direction, kws, msg, t, company)
        # 去掉 CTA 再试
        for sk in skeletons:
            msg = sk.format(school=school, grade=grade, major=major, name=name,
                            title=t or '这个岗位', proof=proof, av=av, cta='')
            msg = re.sub(r'\s+', ' ', msg).strip().replace('。。', '。')
            if len(msg) <= max_len and msg not in used:
                used.add(msg)
                return _rec(job, direction, kws, msg, t, company)

    msg = skeletons[0].format(school=school, grade=grade, major=major, name=name,
                              title=title, proof=proof, av=av, cta='')
    return _rec(job, direction, kws, re.sub(r'\s+', ' ', msg).strip(), title, company)


def _rec(job, direction, kws, msg, title, company):
    match = len([k for k in kws if k in MY_SKILLS])
    return {
        'title': title, 'company': company,
        'city': norm(job.get('city')), 'salary': norm(job.get('salary')),
        'url': norm(job.get('url')), 'direction': direction, 'keywords': kws,
        'match': match, 'message': msg, 'length': len(msg),
    }


def quality_check(rec, profile):
    problems = []
    msg = rec['message']
    if rec['length'] > profile.get('max_len', 110):
        problems.append('过长')
    if rec['length'] < profile.get('min_len', 55):
        problems.append('过短')
    if '希望收到您的回复' in msg:
        problems.append('套话')
    if not rec['keywords']:
        problems.append('未命中技术关键词')
    if '每周' not in msg and '个月' not in msg and '到岗' not in msg:
        problems.append('缺可实习时间')
    return problems


def load_xlsx(path, sheet):
    from openpyxl import load_workbook
    wb = load_workbook(path, read_only=True, data_only=True)
    ws = wb[sheet] if sheet in wb.sheetnames else wb.worksheets[0]
    rows = list(ws.iter_rows(values_only=True))
    if not rows:
        return []
    header = [str(c or '').strip() for c in rows[0]]
    idx = {h: i for i, h in enumerate(header)}

    def cell(row, *names):
        for n in names:
            if n in idx and idx[n] < len(row):
                v = row[idx[n]]
                if v is not None:
                    return str(v).strip()
        return ''

    out = []
    for row in rows[1:]:
        title = cell(row, '名称', '岗位', '职位名称')
        if not title:
            continue
        out.append({
            'title': title, 'company': cell(row, '公司', '公司名称'),
            'city': cell(row, '地点', '城市'), 'salary': cell(row, '薪资', '实习薪资'),
            'stack': cell(row, '技术栈'), 'jd': cell(row, '要求', '岗位要求', '职责'),
            'url': cell(row, '职位链接', '链接', 'url'),
        })
    return out


def load_json(path):
    data = json.load(open(path, encoding='utf-8'))
    if isinstance(data, dict):
        data = data.get('jobs') or []
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--input', required=True)
    ap.add_argument('--sheet', default='实习岗位')
    ap.add_argument('--profile', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'my_profile.json'))
    ap.add_argument('--out-dir', default='out')
    ap.add_argument('--keep-blacklist', action='store_true')
    args = ap.parse_args()

    profile = json.load(open(args.profile, encoding='utf-8'))
    jobs = load_xlsx(args.input, args.sheet) if args.input.lower().endswith('.xlsx') else load_json(args.input)
    if not jobs:
        print('没有读到岗位，检查表头是否为：名称/技术栈/公司/地点')
        sys.exit(1)

    rng = random.Random(20261102)
    used, records, seen = set(), [], set()
    for job in jobs:
        rec = build_greeting(job, profile, rng, used)
        blocked = is_blacklisted(rec['title'], rec['company'])
        key = (rec['company'], rec['title'])
        dup = key in seen
        seen.add(key)
        rec['blacklist'] = blocked
        rec['duplicate'] = dup
        rec['problems'] = quality_check(rec, profile)
        rec['send'] = bool(not blocked and not dup and not rec['problems'] and not args.keep_blacklist)
        records.append(rec)

    os.makedirs(args.out_dir, exist_ok=True)
    queue = [{'company': r['company'], 'title': r['title'], 'city': r['city'],
              'url': r['url'], 'message': r['message'], 'direction': r['direction'],
              'match': r['match']} for r in records if r['send']]
    json.dump({'generated_from': os.path.basename(args.input), 'count': len(queue), 'jobs': queue},
              open(os.path.join(args.out_dir, 'queue.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)

    try:
        from openpyxl import Workbook
        from openpyxl.styles import Font, PatternFill, Alignment
        wb = Workbook()
        ws = wb.active
        ws.title = '打招呼语'
        ws.append(['公司', '岗位', '城市', '方向', '命中关键词', '匹配度', '字数', '打招呼语', '可发送', '问题'])
        for c in ws[1]:
            c.font = Font(bold=True, color='FFFFFF')
            c.fill = PatternFill('solid', fgColor='1F6F63')
        for r in records:
            flag = '是' if r['send'] else ('黑名单' if r['blacklist'] else ('重复' if r['duplicate'] else '否'))
            ws.append([r['company'], r['title'], r['city'], r['direction'], '、'.join(r['keywords']),
                       r['match'], r['length'], r['message'], flag, '、'.join(r['problems'])])
        for i, w in enumerate([20, 34, 18, 11, 16, 7, 6, 72, 9, 14], start=1):
            ws.column_dimensions[chr(64 + i)].width = w
        for row in ws.iter_rows(min_row=2):
            row[7].alignment = Alignment(wrap_text=True, vertical='top')
        wb.save(os.path.join(args.out_dir, 'greetings.xlsx'))
    except ImportError:
        pass

    ok = sum(1 for r in records if r['send'])
    print('岗位 %d｜可发送 %d｜黑名单 %d｜重复 %d｜需修正 %d' % (
        len(records), ok, sum(1 for r in records if r['blacklist']),
        sum(1 for r in records if r['duplicate']),
        sum(1 for r in records if r['problems'] and not r['blacklist'] and not r['duplicate'])))
    print('输出：%s' % os.path.abspath(args.out_dir))


if __name__ == '__main__':
    main()
