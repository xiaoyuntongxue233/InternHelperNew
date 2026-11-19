/**
 * 备用发送路径：从「消息列表」点进已有会话再发。
 *
 * 用途：主脚本 send_boss.mjs 的就地路径失败时，可以用这条路径单独补发。
 * 典型场景是「会话已经建出来、但主流程没走通」的岗位。
 *
 * 用法：
 *   node send_by_company.mjs --plan queue_fresh.json --send --limit 3
 *   node send_by_company.mjs --plan send_plan_missing.json --dry-run
 *
 * 队列格式兼容两种：{count, jobs:[...]} 和裸数组。
 * 不写 --send 一律演练，不会发出任何消息。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensurePage, connectPage, makeEval, activate, CHAT_URL } from './cdp_common.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.CDP_PORT || 9333);

function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  if (i === -1) return def;
  const v = process.argv[i + 1];
  return v && !v.startsWith('--') ? v : true;
}

const PLAN = path.resolve(HERE, String(arg('plan', 'queue_fresh.json')));
const LIMIT = Number(arg('limit', 3));
const SEND = !!arg('send', false);
const DELAY_MIN = Number(arg('delay-min', 20));
const DELAY_MAX = Number(arg('delay-max', 35));
const STOP_ON_ERROR = Number(arg('stop-on-error', 3));
const ALLOW_PLACEHOLDER = !!arg('allow-placeholder', false);

// 与 send_boss.mjs 保持一致：脱敏后的占位话术一律不发
const PLACEHOLDER_RE = /XX大学|XX专业|（示例）|示例姓名|张三/;
const looksPlaceholder = (s) => PLACEHOLDER_RE.test(String(s || ''));

const NORM_WS = /[\s\u00a0\u200b-\u200d\ufeff\u3000]/g;
const norm = (s) => String(s ?? '').replace(NORM_WS, '');
const strip = (s) => String(s || '').replace(/\.\.\.|…/g, '').replace(NORM_WS, '').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (a, b) => a + Math.random() * (b - a);
const L = (...a) => console.log(...a);

function todayKey() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

// ---- 读队列（兼容两种 schema）
if (!fs.existsSync(PLAN)) {
  console.error('找不到计划文件：' + PLAN);
  process.exit(1);
}
const raw = JSON.parse(fs.readFileSync(PLAN, 'utf8'));
const jobs = (Array.isArray(raw) ? raw : (raw.jobs || [])).filter((j) => j && j.company && j.message);
if (!jobs.length) {
  console.error(`${PLAN} 里没有可用的岗位（需要 company + message）`);
  process.exit(1);
}

// ---- 跳过历史已发过的公司
const LOG = path.resolve(HERE, 'out/send_log.jsonl');
const sentBefore = new Set();
if (fs.existsSync(LOG)) {
  for (const line of fs.readFileSync(LOG, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    let r; try { r = JSON.parse(line); } catch { continue; }
    const st = r.status || (r.ok ? 'sent' : 'failed');
    if (st === 'sent') sentBefore.add(strip(r.company));
  }
}

// ---- 脱敏占位话术一律不发
const phCount = jobs.filter((j) => looksPlaceholder(j.message)).length;
if (phCount && !ALLOW_PLACEHOLDER) {
  if (phCount === jobs.length) {
    console.error(`✗ 计划里全部 ${phCount} 条话术都是脱敏占位文本（XX大学 / XX专业 / 张三）。`);
    console.error('  这样发出去，HR 收到的是占位符。请先重新生成话术。');
    process.exit(1);
  }
  L(`⚠ 计划里有 ${phCount} 条话术含脱敏占位符，这些会被跳过`);
}

L(`计划 ${jobs.length} 个｜本次上限 ${LIMIT}｜模式：${SEND ? '真实发送' : '演练'}`);

if (!SEND) {
  let shown = 0;
  for (const j of jobs) {
    if (shown >= LIMIT) break;
    if (sentBefore.has(strip(j.company))) { L(`跳过（已发过）：${strip(j.company)}`); continue; }
    const ph = looksPlaceholder(j.message) ? '　（占位文本，不会发送）' : '';
    L(`[${shown + 1}] ${strip(j.company)} / ${j.title || ''}${ph}`);
    L('  将发送：' + j.message);
    shown++;
  }
  L(`\n演练结束，共 ${shown} 条待发。确认后加 --send。`);
  process.exit(0);
}

// ---- 连浏览器
const page = await ensurePage(PORT, { startUrl: CHAT_URL });
if (!page) {
  console.error(`连不上调试端口 ${PORT}，或开不出标签页。`);
  process.exit(1);
}
const cdp = await connectPage(page.webSocketDebuggerUrl);
await activate(cdp);
const ev = await makeEval(cdp);

await cdp.send('Page.enable');
await cdp.send('Page.navigate', { url: CHAT_URL });

let listed = false;
for (let i = 0; i < 25 && !listed; i++) {
  await sleep(1000);
  listed = await ev(`document.querySelectorAll('.friend-content').length > 0`).catch(() => false);
}
if (!listed) {
  console.error('会话列表没加载出来，先跑 node check_auth2.mjs 确认登录态。');
  cdp.close();
  process.exit(1);
}

const readInputState = async () => {
  const rawJson = await ev(`(() => {
    const el = document.querySelector('#chat-input');
    return JSON.stringify({ exists: !!el, text: el ? (el.innerText || '') : '' });
  })()`).catch(() => null);
  if (!rawJson) return { exists: false, text: '' };
  try { const s = JSON.parse(rawJson); return { exists: !!s.exists, text: String(s.text || '') }; }
  catch { return { exists: false, text: '' }; }
};

const clickPoint = async (x, y) => {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
};

const clickFriend = (company, title) => ev(`(() => {
  const want = ${JSON.stringify(company)};
  const wantTitle = ${JSON.stringify(String(title || ''))};
  const N = /[\\s\\u00a0\\u200b-\\u200d\\ufeff\\u3000]/g;
  const clean = (s) => String(s || '').replace(/\\.\\.\\.|…/g, '').replace(N, '');
  const items = [...document.querySelectorAll('.friend-content')];
  const cand = [];
  for (const el of items) {
    const raw = el.innerText || '';
    const text = clean(raw);
    if (!want || !text.includes(want)) continue;
    const firstLine = clean(raw.split('\\n')[0] || '');
    let score = 0;
    if (firstLine === want) score += 100;
    else if (firstLine.startsWith(want)) score += 60;
    else if (text.startsWith(want)) score += 50;
    const tl = clean(wantTitle).slice(0, 8);
    if (tl && tl.length >= 4 && text.includes(tl)) score += 20;
    cand.push({ el, score });
  }
  if (!cand.length) return 'notfound';
  cand.sort((a, b) => b.score - a.score);
  const el = cand[0].el;
  el.scrollIntoView({ block: 'center' });
  for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'])
    el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window, button: 0 }));
  return 'clicked';
})()`).catch(() => 'err');

const titleOf = () => ev(`(() => {
  const box = document.querySelector('.chat-conversation');
  if (!box) return '';
  for (const s of ['.boss-name', '.chat-title', '.title-name', '.name']) {
    const el = box.querySelector(s);
    if (el && el.innerText && el.innerText.trim()) return el.innerText.trim().slice(0, 40);
  }
  return '';
})()`).catch(() => '');

const mineCount = () => ev(`document.querySelectorAll('.chat-conversation .message-item.item-myself').length`).catch(() => 0);
const lastMine = () => ev(`(() => { const n = document.querySelectorAll('.chat-conversation .message-item.item-myself');
  return n.length ? n[n.length - 1].innerText.replace(/\\s+/g, ' ').slice(0, 200) : ''; })()`).catch(() => '');

let done = 0, skipped = 0, failed = 0, streak = 0;

for (const item of jobs) {
  if (done >= LIMIT) break;
  const company = strip(item.company);
  const title = item.title || '';

  if (sentBefore.has(company)) { L(`跳过（已发过）：${company}`); skipped++; continue; }

  if (looksPlaceholder(item.message) && !ALLOW_PLACEHOLDER) {
    L(`跳过（话术是脱敏占位文本）：${company}`);
    skipped++;
    continue;
  }

  L(`\n>>> ${company}`);

  // 每次从列表页开始，保证状态干净
  await cdp.send('Page.navigate', { url: CHAT_URL });
  let ok = false;
  for (let i = 0; i < 20 && !ok; i++) {
    await sleep(500);
    ok = await ev(`document.querySelectorAll('.friend-content').length > 0`).catch(() => false);
  }
  if (!ok) { L('  列表没加载出来'); failed++; streak++; if (streak >= STOP_ON_ERROR) break; continue; }

  const clicked = await clickFriend(company, title);
  if (clicked !== 'clicked') { L('  列表里找不到这家公司'); skipped++; continue; }
  await sleep(2500);

  const st0 = await readInputState();
  if (!st0.exists) { L('  聊天框没出来'); failed++; streak++; if (streak >= STOP_ON_ERROR) break; continue; }

  // 归属校验：只看标题元素，不把消息正文算进来
  const head = strip(await titleOf());
  if (head && !sameCompanyLike(head, company)) {
    L(`  × 会话标题对不上（${head}），不发`);
    failed++; streak++;
    if (streak >= STOP_ON_ERROR) break;
    continue;
  }

  const before = Number(await mineCount()) || 0;
  if (before > 0) { L(`  会话里已有 ${before} 条我的消息，跳过`); skipped++; continue; }

  // 清空输入框
  await ev(`(() => { const el = document.querySelector('#chat-input'); if (!el) return;
    el.focus(); document.execCommand('selectAll'); document.execCommand('delete'); })()`).catch(() => {});
  await sleep(300);
  const st1 = await readInputState();
  if (!st1.exists || norm(st1.text) !== '') { L('  输入框清不干净或有残留，跳过'); skipped++; continue; }

  // 真实鼠标点输入框
  const posJson = await ev(`(() => { const el = document.querySelector('#chat-input'); if (!el) return '';
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return '';
    return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 }); })()`).catch(() => '');
  let pos;
  try { pos = JSON.parse(posJson); } catch { L('  定位不到输入框'); failed++; streak++; continue; }

  await clickPoint(pos.x, pos.y);
  await sleep(400);
  await cdp.send('Input.insertText', { text: item.message });
  await sleep(900);

  const st2 = await readInputState();
  if (!st2.exists || norm(st2.text) !== norm(item.message)) {
    L('  输入未生效，跳过（不留草稿）');
    await ev(`(() => { const el = document.querySelector('#chat-input'); if (!el) return;
      el.innerText = ''; el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' })); })()`).catch(() => {});
    failed++; streak++;
    if (streak >= STOP_ON_ERROR) break;
    continue;
  }

  await clickPoint(pos.x, pos.y);
  await sleep(300);
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });

  // 轮询等清空，不要固定等 1.8 秒（否则可能补点出第二条）
  const t0 = Date.now();
  let cleared = false;
  while (Date.now() - t0 < 6000) {
    await sleep(200);
    const st = await readInputState();
    if (!st.exists || norm(st.text) === '') { cleared = true; break; }
  }
  await sleep(1500);

  const after = Number(await mineCount()) || 0;
  const last = String(await lastMine() || '');
  const landed = last.includes(norm(item.message).slice(0, 24));

  if (cleared && after > before && landed) {
    L(`  ✓ 已发送（我方消息 ${before}→${after}）`);
    done++; streak = 0; sentBefore.add(company);
    fs.appendFileSync(LOG, JSON.stringify({
      time: new Date().toISOString(), localDay: todayKey(),
      company, title, url: item.url || '', message: item.message,
      status: 'sent', ok: true, reason: 'ok', how: 'enter', stage: 'by-company',
      lastMyMsg: last,
    }) + '\n');
  } else {
    L(`  × 未确认发送（输入框${cleared ? '已清空' : '仍有内容'}，我方消息 ${before}→${after}）`);
    failed++; streak++;
    fs.appendFileSync(LOG, JSON.stringify({
      time: new Date().toISOString(), localDay: todayKey(),
      company, title, url: item.url || '',
      status: 'failed', ok: false, reason: !cleared ? 'input-not-cleared' : 'no-new-message',
      how: 'enter', stage: 'by-company', lastMyMsg: last,
    }) + '\n');
    if (streak >= STOP_ON_ERROR) { L('连续失败过多，停止。'); break; }
  }

  const w = rand(DELAY_MIN, DELAY_MAX);
  L(`  …等待 ${w.toFixed(0)} 秒`);
  await sleep(w * 1000);
}

L(`\n完成：成功 ${done}｜跳过 ${skipped}｜失败 ${failed}`);
cdp.close();
process.exit(0);

/** 标题里可能写成「公司名-岗位」，做包含式比较 */
function sameCompanyLike(head, company) {
  const h = strip(head);
  const c = strip(company);
  if (!h || !c) return true;
  if (h.includes(c)) return true;
  if (c.includes(h.slice(0, Math.min(h.length, 8)))) return true;
  return false;
}
