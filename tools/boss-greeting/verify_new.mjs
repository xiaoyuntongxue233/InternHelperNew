/**
 * 服务端核对：按公司名查历史消息，确认最后一条是不是我的话术。
 *
 * 用法：node verify_new.mjs 公司A 公司B
 *       node verify_new.mjs --all        核对今天日志里所有 status:sent 的公司
 *
 * 姓名/学校不再硬编码在脚本里，从 my_profile.json 读（红线：不把真实姓名提交进仓库）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensurePage, connectPage, activate } from './cdp_common.mjs';

const PORT = Number(process.env.CDP_PORT || 9333);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const LOG = path.resolve(HERE, 'out/send_log.jsonl');

// ---- 从 my_profile.json 读本人标识；读不到就用空数组（只按「有消息」判断）
const PLACEHOLDER = /XX大学|XX专业|（示例）|示例姓名|张三/;

function loadMarkers() {
  const markers = [];
  const p = path.resolve(HERE, 'my_profile.json');
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    for (const k of ['name', 'school', 'school_full', 'university', 'realName']) {
      const v = j[k];
      if (typeof v === 'string' && v.trim().length >= 2) markers.push(v.trim());
    }
    if (Array.isArray(j.markers)) for (const m of j.markers) {
      if (typeof m === 'string' && m.trim().length >= 2) markers.push(m.trim());
    }
  } catch { }
  // my_profile.json 里没有时，退回用话术里必然出现的学校简称（不含人名）
  if (!markers.length) markers.push('XX大学');
  return [...new Set(markers)];
}

const MARKERS = loadMarkers();
// 脱敏之后标识全是占位符，拿它比对真实历史消息必然全部不命中。
// 这种情况下只报「有没有消息」，不再谎报「没有消息」。
const REAL_MARKERS = MARKERS.filter((m) => !PLACEHOLDER.test(m));
const PLACEHOLDER_MODE = REAL_MARKERS.length === 0;
const markerRe = REAL_MARKERS.length
  ? new RegExp(REAL_MARKERS.map((m) => m.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'))
  : null;

function todayKey() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** --all：从日志取今天成功发送的公司名 */
function namesFromLog() {
  const out = new Set();
  if (!fs.existsSync(LOG)) return [];
  for (const line of fs.readFileSync(LOG, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    let r; try { r = JSON.parse(line); } catch { continue; }
    const st = r.status || (r.ok ? 'sent' : 'failed');
    if (st !== 'sent') continue;
    const isToday = r.localDay ? r.localDay === todayKey()
      : (r.time && new Date(r.time).toDateString() === new Date().toDateString());
    if (!isToday) continue;
    const name = String(r.company || '').replace(/\.\.\.|…/g, '').trim();
    if (name) out.add(name);
  }
  return [...out];
}

const argv = process.argv.slice(2);
const names = argv.includes('--all') ? namesFromLog() : argv.filter((a) => !a.startsWith('--'));
if (!names.length) {
  console.error('用法：node verify_new.mjs 公司A 公司B　或　node verify_new.mjs --all');
  process.exit(1);
}

const page = await ensurePage(PORT, { startUrl: 'https://www.zhipin.com/web/geek/chat' });
if (!page) {
  console.error(`连不上调试端口 ${PORT}，或开不出标签页。`);
  process.exit(1);
}

const cdp = await connectPage(page.webSocketDebuggerUrl);
await activate(cdp);
await cdp.send('Runtime.enable');

// 关键：在页面上下文里发请求。
// Node 侧自己拼 cookie 头会缺 __zp_stoken__ / UA / referer，接口返回空列表。
const ev = async (expr, t = 25000) => {
  const r = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }, t);
  if (r?.exceptionDetails) throw new Error(r.exceptionDetails.text || 'evaluate error');
  return r?.result?.value;
};

const get = async (p) => {
  const raw = await ev(`fetch(${JSON.stringify(p)}, { credentials: 'include',
    headers: { 'x-requested-with': 'XMLHttpRequest' } }).then(r => r.text()).catch(e => 'ERR:' + e.message)`);
  try { return JSON.parse(String(raw)); }
  catch { throw new Error('接口返回非 JSON：' + String(raw).slice(0, 120)); }
};

// 先确认登录态，避免把「掉登录」误判成「列表为空」
const info = await get('/wapi/zpuser/wap/getUserInfo.json').catch(() => ({}));
if (info && info.code !== undefined && info.code !== 0) {
  console.error(`登录态无效（code:${info.code}）。先跑 node check_auth2.mjs 或 node go_login.mjs`);
  process.exit(1);
}

// 翻页拉会话列表。注意接口会自己把 size 压到 20 左右，
// 所以不能按「返回数 < 请求数」判到底，只能按「返回空 / 没有新条目」判到底。
const listAll = [];
const seen = new Set();
for (let pageNo = 1; pageNo <= 10; pageNo++) {
  const j = await get(`/wapi/zprelation/friend/getGeekFriendList.json?page=${pageNo}&size=100`).catch(() => ({}));
  const arr = j.zpData?.result || [];
  if (!arr.length) break;
  let added = 0;
  for (const it of arr) {
    const k = it.encryptBossId || it.securityId || it.brandName;
    if (k && seen.has(k)) continue;
    if (k) seen.add(k);
    listAll.push(it);
    added++;
  }
  if (!added) break;
  await new Promise((r) => setTimeout(r, 400));
}
console.log(`会话列表共 ${listAll.length} 条`);

const normName = (s) => String(s || '').replace(/\.\.\.|…/g, '').replace(/[\s\u00a0\u3000]/g, '');

const out = [];
for (const n of names) {
  const want = normName(n);

  // 同一家公司常有多个会话（不同岗位/不同 HR），必须全部查一遍，
  // 只看第一个会撞上空会话从而误报「没有消息」。
  const cands = listAll.filter((x) => {
    const b = normName(x.brandName);
    return b === want || (b.startsWith(want) && want.length >= 2) || (x.brandName || '').includes(n);
  });
  // 完全相等的排前面
  cands.sort((a, b) => (normName(b.brandName) === want ? 1 : 0) - (normName(a.brandName) === want ? 1 : 0));

  if (!cands.length) { out.push(`${n}: 列表里没找到`); continue; }

  let hit = null, checked = 0, anyMsg = 0, scanned = 0;
  for (const f of cands.slice(0, 8)) {
    checked++;
    let h;
    try {
      h = await get(`/wapi/zpchat/geek/historyMsg?bossId=${f.encryptBossId}&maxMsgId=0&c=20&page=1&src=0&securityId=${encodeURIComponent(f.securityId)}`);
    } catch { continue; }
    const texts = (h.zpData?.messages || []).map((m) => {
      const b = typeof m.body === 'object' ? m.body : (() => { try { return JSON.parse(m.body); } catch { return {}; } })();
      return b.text || '';
    }).filter(Boolean);
    scanned++;
    if (texts.length) anyMsg++;

    // 脱敏模式下没有可比对的标识，只能确认「这个会话里有没有消息」
    if (PLACEHOLDER_MODE) continue;

    const mine = texts.filter((t) => markerRe && markerRe.test(t));
    if (mine.length) {
      hit = { f, last: mine[mine.length - 1], count: mine.length };
      break;
    }
    await new Promise((r) => setTimeout(r, 250));
  }

  if (PLACEHOLDER_MODE) {
    out.push(anyMsg
      ? `${n}: ✓ 有消息（${anyMsg}/${checked} 个会话非空）｜内容未比对：话术已脱敏`
      : `${n}: × 所有会话都为空（查了 ${checked}/${cands.length} 个）`);
  } else {
    out.push(hit
      ? `${n}: ✓ 已送达（共 ${cands.length} 个会话，第 ${checked} 个命中）｜${hit.last.slice(0, 46)}`
      : `${n}: × 没有命中我的话术（查了 ${checked}/${cands.length} 个会话）`);
  }
  await new Promise((r) => setTimeout(r, 250));
}

if (PLACEHOLDER_MODE) {
  console.log('\n注意：my_profile.json 里只剩占位符（已脱敏），本次只核对「有没有消息」，不比对内容。');
  console.log('      要恢复内容比对，把真实姓名/学校填回 my_profile.json 即可。');
}

fs.writeFileSync(path.resolve(HERE, 'verify_new.txt'), out.join('\n'), 'utf-8');
console.log(out.join('\n'));
cdp.close();
process.exit(0);
