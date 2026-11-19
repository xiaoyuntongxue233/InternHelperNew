/**
 * 核心逻辑回归测试（不打开浏览器、不发送任何消息）
 *
 * 运行：node test_core_logic.mjs
 *
 * 覆盖改造中最容易出错、且无法靠"看代码"确认的四块：
 *   1. 公司名比较 sameCompany / stripCompanyNoise
 *   2. 话术落地判定 messageLanded（旧实现用固定模板开头，区分度为零）
 *   3. 日志三态 recStatus / sentToday / loadSkip（跳过不得被永久拉黑）
 *   4. 队尾重试排序 orderJobs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TMP_LOG = path.join(HERE, 'out', '_test_log.jsonl');

// 让 send_boss 的模块级常量指向临时日志，避免污染真实 send_log.jsonl
process.argv = ['node', 'test_core_logic.mjs', '--log', TMP_LOG, '--queue', 'queue_fresh.json'];

fs.mkdirSync(path.join(HERE, 'out'), { recursive: true });

const todayKey = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};
const TODAY = todayKey();
const OLD = '2026-01-01';

// 构造一份混合日志：今天成功/失败/跳过 + 一条老成功 + 一条只有 UTC time 的老记录
fs.writeFileSync(TMP_LOG, [
  { time: new Date().toISOString(), localDay: TODAY, company: '甲公司', status: 'sent', ok: true, reason: 'ok' },
  { time: new Date().toISOString(), localDay: TODAY, company: '乙公司', status: 'failed', ok: false, reason: 'no-chat' },
  { time: new Date().toISOString(), localDay: TODAY, company: '丙公司', status: 'skipped', ok: false, reason: 'empty-chat' },
  { time: '2026-01-01T08:00:00.000Z', localDay: OLD, company: '丁公司', status: 'sent', ok: true, reason: 'ok' },
  // 老格式：没有 status / localDay，只有 ok 布尔
  { time: '2026-01-01T09:00:00.000Z', company: '戊公司', ok: true },
].map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8');

const sb = await import('./send_boss.mjs');
const {
  sameCompany, stripCompanyNoise, messageLanded,
  recStatus, sentToday, loadSkip, orderJobs, norm, looksPlaceholder,
} = sb;

let pass = 0, fail = 0;
const t = (name, fn) => {
  try { fn(); pass++; console.log('  ✓ ' + name); }
  catch (e) { fail++; console.log('  ✗ ' + name + '\n      ' + e.message); }
};

console.log('\n[1] 公司名比较');
t('完全相等', () => assert.equal(sameCompany('字节跳动', '字节跳动'), true));
t('城市前缀可忽略', () => assert.equal(sameCompany('北京字节跳动', '字节跳动'), true));
t('省略号被剥掉', () => assert.equal(sameCompany('北京万科幸福家养...', '北京万科幸福家养'), true));
t('子串不算同一家（旧实现的 bug）', () => assert.equal(sameCompany('小米', '小米生态链科技'), false));
t('空值不匹配', () => assert.equal(sameCompany('', '甲公司'), false));
t('stripCompanyNoise 去空白与省略号', () =>
  assert.equal(stripCompanyNoise(' 北京  万科幸福家养... '), '北京万科幸福家养'));

console.log('\n[2] 话术落地判定');
const MSG_A = '您好，我是XX大学（XX）2028届本科生，本人熟悉Java，有Springboot、MySQL相关经验，现可熟悉使用AI agent相关软件。本人可连续实习至少3个月，每周5天。希望收到您的回复。';
const MSG_B = '您好，我是XX大学（XX）2028届本科生，本人熟悉Python，有数据分析相关经验，现可熟悉使用AI agent相关软件。本人可连续实习至少6个月，每周4天。希望收到您的回复。';

t('自己发的话术能命中', () => assert.equal(messageLanded(MSG_A, MSG_A), true));
t('带多余空白也能命中', () => assert.equal(messageLanded(MSG_A.replace(/\s/g, '\u3000'), MSG_A), true));
t('同模板不同技能句不得命中（旧实现会误判成功）', () =>
  assert.equal(messageLanded(MSG_B, MSG_A), false));
t('只有模板开头相同不得命中', () => {
  const onlyHead = '您好，我是XX大学（XX）2028届本科生。希望收到您的回复。';
  assert.equal(messageLanded(onlyHead, MSG_A), false);
});
t('空字符串不命中', () => assert.equal(messageLanded('', MSG_A), false));

console.log('\n[3] 日志三态');
t('老日志 ok:true 视为 sent', () => assert.equal(recStatus({ ok: true }), 'sent'));
t('老日志 ok:false 视为 failed', () => assert.equal(recStatus({ ok: false }), 'failed'));
t('新日志 status:skipped 优先于 ok 布尔', () =>
  assert.equal(recStatus({ status: 'skipped', ok: false }), 'skipped'));
t('老日志 empty-chat 应归为 skipped 而非 failed', () =>
  assert.equal(recStatus({ ok: false, reason: 'empty-chat' }), 'skipped'));
t('老日志 job-closed 应归为 skipped', () =>
  assert.equal(recStatus({ ok: false, reason: 'job-closed' }), 'skipped'));
t('老日志 no-chat 仍是 failed', () =>
  assert.equal(recStatus({ ok: false, reason: 'no-chat' }), 'failed'));
t('老日志 no-conversation 仍是 failed', () =>
  assert.equal(recStatus({ ok: false, reason: 'no-conversation' }), 'failed'));

const st = sentToday();
t('今日成功数只算今天的 sent', () => assert.equal(st.count, 1));
t('allSent 含历史成功（含老格式）', () => {
  assert.equal(st.allSent.has('甲公司'), true);
  assert.equal(st.allSent.has('丁公司'), true);
  assert.equal(st.allSent.has('戊公司'), true);
});
t('failedToday 只收今天的 failed', () => assert.equal(st.failedToday.has('乙公司'), true));
t('skippedToday 收到今天的 skipped', () => assert.equal(st.skippedToday.has('丙公司'), true));
t('历史失败不进 failedToday', () => assert.equal(st.failedToday.has('甲' === 'x' ? '' : '丁公司'), false));

const skip = loadSkip();
t('历史/今天已发过的进 skip', () => {
  assert.equal(skip.has('甲公司'), true);
  assert.equal(skip.has('丁公司'), true);
});
t('今天明确失败的进 skip（当日节流）', () => assert.equal(skip.has('乙公司'), true));
t('★ 今天「跳过」的不得进 skip（本次核心修复）', () => assert.equal(skip.has('丙公司'), false));

console.log('\n[4] 队尾重试排序');
const jobs = [
  { url: 'u1', company: 'A' },
  { url: 'u2', company: 'B' },
  { url: 'u3', company: 'C' },
  { url: 'u4', company: 'D' },
];
t('失败项整体移到队尾', () => {
  const ordered = orderJobs(jobs, { failed: { u2: { count: 2 } } });
  assert.deepEqual(ordered.map((j) => j.url), ['u1', 'u3', 'u4', 'u2']);
});
t('多个失败项保持相对顺序', () => {
  const ordered = orderJobs(jobs, { failed: { u1: {}, u3: {} } });
  assert.deepEqual(ordered.map((j) => j.url), ['u2', 'u4', 'u1', 'u3']);
});
t('没有失败项时保持原顺序', () => {
  const ordered = orderJobs(jobs, { failed: {} });
  assert.deepEqual(ordered.map((j) => j.url), ['u1', 'u2', 'u3', 'u4']);
});
t('progress 缺字段也不炸', () => assert.equal(orderJobs(jobs, {}).length, 4));

console.log('\n[5] 归一化');
t('全角空格与零宽字符被去除', () =>
  assert.equal(norm('a\u3000b\u200bc\u00a0d'), 'abcd'));

console.log('\n[6] 脱敏占位符闸门');
t('占位学校名被识别', () =>
  assert.equal(looksPlaceholder('您好，我是XX大学（XX）2028届本科生'), true));
t('占位专业被识别', () => assert.equal(looksPlaceholder('本人XX专业在读'), true));
t('占位姓名被识别', () => assert.equal(looksPlaceholder('我是张三'), true));
t('真实话术不误判', () =>
  assert.equal(looksPlaceholder('您好，我是某大学2028届本科生，本人熟悉Java，希望收到您的回复。'), false));
t('空内容不误判', () => assert.equal(looksPlaceholder(''), false));

fs.rmSync(TMP_LOG, { force: true });

console.log(`\n结果：通过 ${pass}，失败 ${fail}`);
process.exit(fail === 0 ? 0 : 1);
