/**
 * 发送 v2：直接走「职位页 → 点立即沟通 → 在会话里输入并发送」
 *
 * 背景：send_boss.mjs 在这条路径上判定会话建立失败，但实测点击后会话是正常打开的，
 *       推测是其内部等待/匹配逻辑过于严格。这里用更长的等待 + 直接校验输入框和最后一条消息。
 *
 * 用法：
 *   node send_v2.mjs --plan queue_fresh.json --dry-run --limit 3
 *   node send_v2.mjs --plan queue_fresh.json --send --limit 5 --delay-min 45 --delay-max 70
 */
import fs from 'node:fs';

const PORT = Number(process.env.CDP_PORT || 9333);
const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.indexOf('--' + name);
  if (i === -1) return def;
  const v = argv[i + 1];
  return v && !v.startsWith('--') ? v : true;
};

const PLAN = String(arg('plan', 'queue_fresh.json'));
const LIMIT = Number(arg('limit', 3));
const SEND = !!arg('send', false);
const DELAY_MIN = Number(arg('delay-min', 45));
const DELAY_MAX = Number(arg('delay-max', 70));
const LOG = 'out/send_v2_log.jsonl';
const DONE = 'out/send_v2_done.json';

const sleep = ms => new Promise(r => setTimeout(r, ms));
const rand = (a, b) => a + Math.random() * (b - a);
const L = (...a) => console.log(...a);

const plan = JSON.parse(fs.readFileSync(PLAN, 'utf-8'));
const jobs = Array.isArray(plan) ? plan : plan.jobs;
const done = fs.existsSync(DONE) ? new Set(JSON.parse(fs.readFileSync(DONE, 'utf-8'))) : new Set();

// ---- 连接 CDP ----
let page = null;
for (let i = 0; i < 20 && !page; i++) {
  try {
    const tabs = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    page = tabs.find(t => t.type === 'page' && t.url.includes('zhipin')) || tabs.find(t => t.type === 'page');
  } catch { }
  if (!page) await sleep(1000);
}
if (!page) { console.error('连不上 9333'); process.exit(1); }

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { const p = pend.get(m.id); pend.delete(m.id); p(m); } };
const send = (method, params = {}, t = 30000) => new Promise(res => {
  const i = ++id; pend.set(i, res);
  ws.send(JSON.stringify({ id: i, method, params }));
  setTimeout(() => { if (pend.has(i)) { pend.delete(i); res({ error: 't' }); } }, t);
});
const ev = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }, 25000);
  return r.result?.result?.value;
};
await send('Page.enable');

// ---- 登录检查 ----
const auth = await ev(`fetch('/wapi/zpuser/wap/getUserInfo.json',{credentials:'include'}).then(r=>r.text()).catch(()=> '')`);
let authCode = -1;
try { authCode = JSON.parse(auth).code; } catch { }
if (authCode !== 0) {
  console.error(`✗ 登录态失效（code=${authCode}），请先在浏览器里登录`);
  process.exit(2);
}
L('登录态正常');

const soft = (s) => String(s || '').replace(/[\s\u00a0\u200b-\u200d\ufeff\u3000]/g, '');

let sent = 0, failed = 0, skipped = 0;

for (const job of jobs) {
  if (sent >= LIMIT) break;
  if (!job.url || done.has(job.company)) continue;

  L(`\n[${sent + 1}] ${job.company} / ${job.title}`);
  if (!SEND) { L('  演练：' + job.message); sent++; continue; }

  // 把话术放进页面上下文，供回退方案使用
  await ev(`window.__msg = ${JSON.stringify(job.message)}; true`);

  await send('Page.navigate', { url: job.url });
  await sleep(6500);

  const txt = String(await ev(`(document.body.innerText||'').slice(0,300)`) || '');
  if (/职位已关闭|查看更多优选职位/.test(txt)) {
    L('  – 岗位已下线，跳过'); skipped++; done.add(job.company); continue;
  }
  if (/安全验证|请完成验证|拖动滑块/.test(txt)) {
    L('  ✗ 触发安全验证，停止'); failed++; break;
  }

  // 点「立即沟通 / 继续沟通」
  const box = await ev(`(() => {
    const b = [...document.querySelectorAll('a,button,span,div')].find(e => /^(立即沟通|继续沟通)$/.test((e.innerText||'').trim()));
    if (!b) return null;
    const r = b.getBoundingClientRect();
    if (!r.width) return null;
    return { x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2), label: b.innerText.trim() };
  })()`);
  if (!box) { L('  × 找不到沟通按钮'); failed++; continue; }

  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
  }
  L(`  已点击「${box.label}」，等待会话加载…`);
  await sleep(7000);

  const state = await ev(`(() => {
    const input = document.querySelector('#chat-input');
    const mine = document.querySelectorAll('.message-item.item-myself');
    return {
      url: location.href,
      hasInput: !!input,
      empty: input ? input.innerText.trim().length === 0 : null,
      mineCount: mine.length,
      lastMine: mine.length ? (mine[mine.length - 1].innerText || '').slice(0, 60) : null,
      total: document.querySelectorAll('.message-item').length
    };
  })()`);
  L(`  会话状态：input=${state.hasInput} 消息数=${state.total} 我的消息=${state.mineCount}`);

  if (!state.hasInput) { L('  × 没有输入框'); failed++; continue; }

  // 已经发过就跳过
  const mineTexts = await ev(`(() => {
    const arr = [...document.querySelectorAll('.message-item.item-myself')];
    return arr.map(e => (e.innerText || '')).join('\\n');
  })()`);
  if (/XX大学/.test(String(mineTexts))) {
    L('  跳过：这个会话里已经有我的话术');
    skipped++; done.add(job.company); continue;
  }

  // 聚焦输入框：先滚动到可视区 + JS focus，再点一次
  await ev(`(() => {
    const el = document.querySelector('#chat-input');
    if (!el) return false;
    el.scrollIntoView({ block: 'center' });
    el.focus();
    return true;
  })()`);
  await sleep(400);

  const ibox = await ev(`(() => {
    const el = document.querySelector('#chat-input');
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2) };
  })()`);
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: ibox.x, y: ibox.y, button: 'left', clickCount: 1 });
  }
  await sleep(600);

  await send('Input.insertText', { text: job.message });
  await sleep(900);

  const readLen = async () => Number(await ev(`((document.querySelector('#chat-input')||{}).innerText||'').trim().length`)) || 0;
  let typed = await readLen();

  // 回退 1：execCommand 插入
  if (typed < 20) {
    await ev(`(() => {
      const el = document.querySelector('#chat-input');
      el.focus();
      document.execCommand('insertText', false, window.__msg || '');
      return true;
    })()`);
    await sleep(600);
    typed = await readLen();
  }

  // 回退 2：直接写 innerText + 触发 input 事件（Vue 受控组件）
  if (typed < 20) {
    await ev(`(() => {
      const el = document.querySelector('#chat-input');
      el.focus();
      el.innerText = window.__msg || '';
      el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: window.__msg }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return el.innerText.length;
    })()`);
    await sleep(700);
    typed = await readLen();
  }

  if (typed < 20) {
    L(`  × 文本没写进去（长度 ${typed}）`);
    failed++;
    continue;
  }

  // 发送：优先点发送按钮，否则回车
  const clicked = await ev(`(() => {
    const b = [...document.querySelectorAll('button, .btn-send, [class*="send"]')]
      .find(e => /发送/.test(e.innerText || '') && !e.disabled);
    if (b) { b.click(); return true; }
    return false;
  })()`);
  if (!clicked) {
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  }
  await sleep(2500);

  const after = await ev(`(() => {
    const arr = [...document.querySelectorAll('.message-item.item-myself')];
    const last = arr.length ? (arr[arr.length - 1].innerText || '') : '';
    const input = document.querySelector('#chat-input');
    return { last, inputLen: input ? input.innerText.trim().length : -1, count: arr.length };
  })()`);

  if (/XX大学/.test(String(after.last)) && after.count > state.mineCount) {
    L('  ✓ 已发送');
    sent++;
    done.add(job.company);
    fs.appendFileSync(LOG, JSON.stringify({
      time: new Date().toISOString(), company: job.company, title: job.title, ok: true, message: job.message,
    }) + '\n');
  } else {
    L(`  × 发送未确认（输入框残留 ${after.inputLen}）`);
    failed++;
    fs.appendFileSync(LOG, JSON.stringify({
      time: new Date().toISOString(), company: job.company, title: job.title, ok: false, reason: 'verify-failed',
    }) + '\n');
  }

  fs.writeFileSync(DONE, JSON.stringify([...done]), 'utf-8');
  const wait = rand(DELAY_MIN, DELAY_MAX);
  L(`  …等待 ${Math.round(wait)} 秒`);
  await sleep(wait * 1000);
}

fs.writeFileSync(DONE, JSON.stringify([...done]), 'utf-8');
L(`\n完成：成功 ${sent}｜跳过 ${skipped}｜失败 ${failed}`);
process.exit(0);
