#!/usr/bin/env node
/**
 * BOSS 直聘批量打招呼发送器（Edge + CDP）v4
 *
 *   node send_boss.mjs --selftest --start
 *   node send_boss.mjs --queue out_fresh/queue.json --dry-run
 *   node send_boss.mjs --queue out_fresh/queue.json --send --limit 6
 */

import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ensurePage as ensureBrowserPage, cdpAlive as cdpAliveShared, activate, CHAT_URL } from './cdp_common.mjs';

// ---------------------------------------------------------------- 参数
function arg(name, def = undefined) {
  const i = process.argv.indexOf('--' + name);
  if (i === -1) return def;
  const v = process.argv[i + 1];
  return v && !v.startsWith('--') ? v : true;
}

const KNOWN_ARGS = new Set([
  'queue', 'limit', 'delay-min', 'delay-max', 'send', 'force', 'skip', 'port',
  'profile', 'start', 'stop-on-error', 'log', 'reset', 'selftest', 'dry-run',
  'resume', 'progress', 'daily-cap', 'check', 'help', 'allow-placeholder',
]);

const QUEUE = arg('queue', 'queue_fresh.json');
const LIMIT = Number(arg('limit', 10));
const DELAY_MIN = Number(arg('delay-min', 40));
const DELAY_MAX = Number(arg('delay-max', 90));
const SEND = !!arg('send', false);
const FORCE = !!arg('force', false);
const SKIP_FILE = arg('skip', null);
const PORT = Number(arg('port', process.env.CDP_PORT || 9333));
const PROFILE = path.resolve(arg('profile', './.boss-profile'));
const AUTOSTART = !!arg('start', false);
const STOP_ON_ERROR = Number(arg('stop-on-error', 3));
const LOG = path.resolve(arg('log', './out/send_log.jsonl'));
const PROGRESS = path.resolve(arg('progress', './out/progress.json'));
const RESUME = !!arg('resume', false);
const DRY_RUN = !!arg('dry-run', false);
const CHECK_ONLY = !!arg('check', false);
const ALLOW_PLACEHOLDER = !!arg('allow-placeholder', false);

/**
 * 脱敏占位符检测。
 * 队列话术被 sanitize_identity.mjs 处理过之后会变成「XX大学（XX）…」这类占位文本，
 * 直接发出去就是把占位符甩给 HR。这里做一道闸：占位话术一律不发。
 */
const PLACEHOLDER_RE = /XX大学|XX专业|（示例）|示例姓名|张三/;
export const looksPlaceholder = (s) => PLACEHOLDER_RE.test(String(s || ''));

// 归一化：\s 不含全角空格/零宽字符/不间断空格，模板里一出现就会让比对永远失败
const NORM_WS = /[\s\u00a0\u200b-\u200d\ufeff\u3000]/g;
export const norm = (s) => String(s ?? '').replace(NORM_WS, '');

// 公司名清洗：截断省略号、所有空白
export const stripCompanyNoise = (s) => String(s || '').replace(/\.\.\.|…/g, '').replace(NORM_WS, '').trim();

// 「北京字节跳动」与「字节跳动」视为同一家；只剥一层城市前缀
const CITY_PREFIX = /^(北京|上海|深圳|广州|杭州|成都|武汉|南京|苏州|天津|西安|重庆|东莞|佛山|珠海|宁波|长沙|郑州|合肥|厦门|青岛|济南|无锡)/;

export function sameCompany(a, b) {
  const x = stripCompanyNoise(a);
  const y = stripCompanyNoise(b);
  if (!x || !y) return false;
  if (x === y) return true;
  const sx = x.replace(CITY_PREFIX, '');
  const sy = y.replace(CITY_PREFIX, '');
  return !!sx && sx === sy;
}

/** 本地日界线（日志里的 time 是 UTC，不能拿它判「今天」） */
const todayKey = () => {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

const EDGE_CANDIDATES = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
];

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const rand = (a, b) => a + Math.random() * (b - a);
const L = (...a) => console.log(...a);

// ---------------------------------------------------------------- 浏览器
async function cdpAlive() {
  return await cdpAliveShared(PORT);
}

function clearProfileLocks() {
  for (const f of ['SingletonLock', 'SingletonCookie', 'SingletonSocket']) {
    try { fs.rmSync(path.join(PROFILE, f), { force: true }); } catch { }
  }
}

async function ensureBrowser() {
  if (!(await cdpAlive())) {
    if (!AUTOSTART) {
      console.error(`调试端口 ${PORT} 没开。加 --start 自动启动，或手动运行：`);
      console.error(`  msedge.exe --remote-debugging-port=${PORT} --user-data-dir="${PROFILE}"`);
      return false;
    }
    const exe = EDGE_CANDIDATES.find(p => fs.existsSync(p));
    if (!exe) { console.error('没找到 Edge 可执行文件'); return false; }

    fs.mkdirSync(PROFILE, { recursive: true });
    clearProfileLocks();

    spawn(exe, [
      `--user-data-dir=${PROFILE}`,
      `--remote-debugging-port=${PORT}`,
      '--no-first-run', '--no-default-browser-check',
      '--window-size=1400,1000',
      CHAT_URL,
    ], { detached: true, stdio: 'ignore' }).unref();

    let up = false;
    for (let i = 0; i < 30; i++) {
      await sleep(1000);
      if (await cdpAlive()) { up = true; break; }
    }
    if (!up) {
      console.error('\n浏览器没有起来。常见原因：');
      console.error('  1) 已有使用同一配置目录的 Edge 在运行 —— 全部关掉再试');
      console.error(`  2) ${PORT} 端口被占用 —— 换一个：--port 9444`);
      return false;
    }
  }

  // 端口活着不等于有页面：Edge 经常会留下一个没有标签页的孤儿进程。
  // ensurePage 会在这种情况下自动 Target.createTarget 开一个。
  const page = await ensureBrowserPage(PORT, { startUrl: CHAT_URL, quiet: false });
  if (!page) {
    console.error(`调试端口 ${PORT} 在，但开不出任何标签页。`);
    console.error(`请手动在 Edge 里打开 ${CHAT_URL} 后重试。`);
    return false;
  }
  return true;
}

// 兼容：旧代码在 ensureBrowser() 之后调 getPage()，这里直接复用 ensurePage 的结果
async function getPage() {
  return await ensureBrowserPage(PORT, { startUrl: CHAT_URL });
}

function connect(wsUrl, timeoutMs = 12000) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    let opened = false;
    const pend = new Map();
    const listeners = new Set();
    const api = {
      send(method, params = {}, timeout = 30000) {
        const mid = ++id;
        try { ws.send(JSON.stringify({ id: mid, method, params })); }
        catch { return Promise.reject(new Error('浏览器连接已断开')); }
        const p = new Promise((res, rej) => pend.set(mid, { res, rej }));
        return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + method)), timeout))]);
      },
      on(fn) { listeners.add(fn); },
      close() { try { ws.close(); } catch { } },
    };
    const timer = setTimeout(() => {
      if (!opened) { try { ws.close(); } catch { } reject(new Error('连不上调试端口（页面可能已关闭）')); }
    }, timeoutMs);
    ws.onopen = () => { opened = true; clearTimeout(timer); resolve(api); };
    ws.onerror = () => { if (!opened) { clearTimeout(timer); reject(new Error('浏览器调试连接失败')); } };
    ws.onclose = () => {
      if (!opened) return;
      for (const [, p] of pend) p.rej(new Error('浏览器连接已断开'));
      pend.clear();
    };
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && pend.has(m.id)) {
        const { res, rej } = pend.get(m.id); pend.delete(m.id);
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
      } else if (m.method) for (const f of listeners) f(m);
    };
  });
}

async function evaluate(cdp, expr, timeout = 15000) {
  const r = await cdp.send('Runtime.evaluate', {
    expression: expr, awaitPromise: true, returnByValue: true,
  }, timeout);
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || ''));
  return r.result?.value;
}

const safeEval = (cdp, expr, t) => evaluate(cdp, expr, t).catch(() => null);

// ---------------------------------------------------------------- 页面动作
async function clickPoint(cdp, x, y) {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}

async function dispatchClick(cdp, selector) {
  return await safeEval(cdp, `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    el.scrollIntoView({ block: 'center' });
    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
      el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window, button: 0 }));
    }
    return true;
  })()`);
}

async function coordClick(cdp, selector, move = false) {
  const pos = await safeEval(cdp, `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return null;
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
  })()`);
  if (!pos) return false;
  const p = JSON.parse(pos);
  if (move) await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y });
  await clickPoint(cdp, p.x, p.y);
  return true;
}

async function clickSelector(cdp, selector, move = false) {
  if (await dispatchClick(cdp, selector)) return true;
  return await coordClick(cdp, selector, move);
}

async function waitFor(cdp, expr, tries = 20, gap = 500) {
  for (let i = 0; i < tries; i++) {
    if (await safeEval(cdp, expr)) return true;
    await sleep(gap);
  }
  return false;
}

// ---------------------------------------------------------------- 输入 / 发送
/**
 * 关键修正：不能把「#chat-input 不存在」读成「输入框是空的」。
 * 旧实现两者都返回 ''，导致页面被导航走时发送校验会退化成只看最后一条旧消息。
 */
async function readInputState(cdp) {
  const raw = await safeEval(cdp, `(() => {
    const el = document.querySelector('#chat-input');
    return JSON.stringify({ exists: !!el, text: el ? (el.innerText || '') : '' });
  })()`);
  if (!raw) return { exists: false, text: '' };
  try {
    const s = JSON.parse(raw);
    return { exists: !!s.exists, text: String(s.text || '') };
  } catch { return { exists: false, text: '' }; }
}

const readInput = async (cdp) => (await readInputState(cdp)).text;

async function clearInput(cdp) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (!el) return;
      el.focus(); document.execCommand('selectAll'); document.execCommand('delete'); })()`);
    await sleep(250);
    if (norm(await readInput(cdp)) === '') return true;

    await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (el) el.focus(); })()`);
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'a', code: 'KeyA', modifiers: 2, windowsVirtualKeyCode: 65 });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers: 2, windowsVirtualKeyCode: 65 });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Backspace', code: 'Backspace', windowsVirtualKeyCode: 8 });
    await sleep(300);
    if (norm(await readInput(cdp)) === '') return true;

    await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (!el) return;
      el.innerText = ''; el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' })); })()`);
    await sleep(300);
    if (norm(await readInput(cdp)) === '') return true;
  }
  return false;
}

async function fillInput(cdp, text) {
  const target = norm(text);
  for (let attempt = 1; attempt <= 4; attempt++) {
    const cleared = await clearInput(cdp);
    if (!cleared) {
      L('    输入框清不干净，不发，避免混进上一家的草稿');
      return false;
    }
    try { await coordClick(cdp, '#chat-input'); } catch { }
    await sleep(200);
    await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (!el) return false;
      el.focus(); const r = document.createRange(); r.selectNodeContents(el); r.collapse(false);
      const s = getSelection(); s.removeAllRanges(); s.addRange(r); return true; })()`);
    await sleep(rand(300, 600));

    if (attempt === 1) {
      try { await cdp.send('Input.insertText', { text }); } catch { L('    输入超时，换个方式重试'); }
    } else if (attempt === 2) {
      await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (!el) return false;
        el.focus(); return document.execCommand('insertText', false, ${JSON.stringify(text)}); })()`);
    } else {
      await safeEval(cdp, `(() => { const el = document.querySelector('#chat-input'); if (!el) return false;
        el.focus(); el.innerText = ${JSON.stringify(text)};
        el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: ${JSON.stringify(text)} }));
        return true; })()`);
    }
    await sleep(rand(500, 900));
    if (norm(await readInput(cdp)) === target) return true;
    L(`    输入未生效（第 ${attempt} 次），重试…`);
  }
  return false;
}

async function waitSendReady(cdp, timeoutMs = 4000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const cls = await safeEval(cdp, `(document.querySelector('button.btn-send') || {}).className || 'none'`);
    if (cls && cls !== 'none' && !String(cls).includes('disabled')) return true;
    await sleep(200);
  }
  return false;
}

const pressEnter = async (cdp) => {
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
};

/**
 * 修正：不要 Enter 后固定等 1.8 秒就补点按钮 —— 渲染稍慢就会被点出第二条招呼。
 * 改成轮询到输入框清空；只有全程没清空、且内容仍是我们写的那段，才允许兜底点按钮。
 */
async function clickSend(cdp, expected) {
  try { await coordClick(cdp, '#chat-input'); } catch { }
  await sleep(rand(250, 450));
  await pressEnter(cdp);

  const t0 = Date.now();
  while (Date.now() - t0 < 6000) {
    await sleep(200);
    const st = await readInputState(cdp);
    if (!st.exists) return 'enter';
    if (norm(st.text) === '') return 'enter';
  }

  const st = await readInputState(cdp);
  if (!st.exists) return 'enter';
  if (expected && norm(st.text) !== norm(expected)) {
    L('    输入框内容已变化，不补点按钮（避免发出第二条）');
    return 'enter';
  }

  const state = await safeEval(cdp, `(() => { const b = document.querySelector('button.btn-send');
    if (!b) return 'nobtn'; b.click(); return 'clicked'; })()`);
  await sleep(1500);
  return state === 'clicked' ? 'clicked' : 'enter';
}

async function lastMyMessage(cdp) {
  return (await safeEval(cdp, `(() => { const n = document.querySelectorAll('.chat-conversation .message-item.item-myself');
    if (!n.length) return '';
    return n[n.length - 1].innerText.replace(/\\s+/g, ' ').slice(0, 200); })()`)) || '';
}

const countMyMessages = async (cdp) =>
  Number(await safeEval(cdp, `document.querySelectorAll('.chat-conversation .message-item.item-myself').length`)) || 0;

/**
 * 判断「我这条话术确实落地了」。
 * 旧实现拿话术前 12 字比对，而模板开头对所有岗位完全相同，区分度为零。
 * 现在取话术**中段**（技能句那一段，逐岗不同）作为指纹。
 */
export function messageLanded(last, message) {
  const a = norm(last);
  const b = norm(message);
  if (!a || !b) return false;
  if (b.length <= 24) return a.includes(b);
  const mid = Math.floor(b.length / 2);
  const fp = b.slice(Math.max(0, mid - 9), mid + 9);   // 18 字指纹
  if (a.includes(fp)) return true;
  return a.includes(b.slice(-14)) && a.includes(b.slice(0, 10));  // 退化：首尾同时命中
}

async function recallLastMine(cdp) {
  const box = await safeEval(cdp, `(() => {
    const n = document.querySelectorAll('.chat-conversation .message-item.item-myself');
    if (!n.length) return null;
    const el = n[n.length - 1];
    el.scrollIntoView({ block: 'center' });
    const r = el.getBoundingClientRect();
    return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
  })()`);
  if (!box) return 'no-message';
  const p = JSON.parse(box);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: p.x, y: p.y });
  await sleep(800);

  const more = await safeEval(cdp, `(() => {
    const nodes = [...document.querySelectorAll('.message-item.item-myself')];
    const last = nodes[nodes.length - 1];
    if (!last) return null;
    const cand = [...last.querySelectorAll('*')].filter(e => e.offsetParent !== null &&
      /^(更多|\\.\\.\\.|⋯)$/.test((e.innerText || '').trim()));
    if (!cand.length) return null;
    const r = cand[cand.length - 1].getBoundingClientRect();
    return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
  })()`);
  if (!more) return 'no-menu';
  const m = JSON.parse(more);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: m.x, y: m.y, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: m.x, y: m.y, button: 'left', clickCount: 1 });
  await sleep(800);

  const undo = await safeEval(cdp, `(() => {
    const el = [...document.querySelectorAll('li,div,span,button')].find(e =>
      (e.innerText || '').trim() === '撤回' && e.offsetParent !== null);
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
  })()`);
  if (!undo) return 'no-recall-item';
  const u = JSON.parse(undo);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: u.x, y: u.y, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: u.x, y: u.y, button: 'left', clickCount: 1 });
  await sleep(1500);
  const ok = await safeEval(cdp, `((document.querySelector('.chat-conversation') || {}).innerText || '').includes('撤回了一条消息')`);
  return ok ? 'recalled' : 'unknown';
}

// ---------------------------------------------------------------- 岗位页
async function gotoJob(cdp, url) {
  try { await cdp.send('Page.navigate', { url }, 30000); } catch { }
  for (let i = 0; i < 30; i++) {
    await sleep(500);
    const st = await safeEval(cdp, `(() => {
      const b = document.querySelector('.btn-startchat');
      const txt = (document.body.innerText || '').slice(0, 3000);
      return JSON.stringify({ ready: !!b, text: b ? b.innerText.trim() : '',
        captcha: /安全验证|请完成验证|访问过于频繁/.test(txt),
        closed: /职位已下线|职位已关闭|该职位已关闭|职位不存在|已停止招聘|无法查看该职位|查看更多优选职位/.test(txt) });
    })()`);
    if (!st) continue;
    const s = JSON.parse(st);
    if (s.captcha) return { ok: false, reason: 'captcha' };
    if (s.closed) return { ok: false, reason: 'closed' };
    if (s.ready) return { ok: true, button: s.text };
  }
  return { ok: false, reason: 'timeout' };
}

/** 只取会话标题元素，不要整个会话区（那里含消息气泡正文） */
async function conversationTitle(cdp) {
  const t = await safeEval(cdp, `(() => {
    const box = document.querySelector('.chat-conversation');
    if (!box) return '';
    const sels = ['.boss-name', '.chat-title', '.title-name', '.name', '[class*="boss-name"]'];
    for (const s of sels) {
      const el = box.querySelector(s);
      if (el && el.innerText && el.innerText.trim()) return el.innerText.trim().slice(0, 40);
    }
    return '';
  })()`);
  return String(t || '');
}

/**
 * 修正：旧实现 head.includes(company) || head.includes(岗位标题前 6 字)。
 * 两个问题：(1) head 是整个会话区文本，含我自己发过的话术，通用片段必然命中；
 * (2) 公司名裸 includes，队列里「小米」会匹配到「小米生态链某公司」。
 * 现在优先只认标题元素，退路也只认公司名（且用规范化比较），不再用岗位标题短片段。
 */
async function conversationMatches(cdp, company, title) {
  const co = stripCompanyNoise(company);
  const head = await conversationTitle(cdp);
  if (head) {
    if (sameCompany(head, co)) return true;
    const h = stripCompanyNoise(head);
    if (co && co.length >= 2 && h.includes(co)) return true;
    // 标题里常写「公司名-岗位名」，公司名可能被截断，反过来也要认
    if (h && h.length >= 4 && co.includes(h.slice(0, Math.min(h.length, 8)))) return true;
    // 标题里确实没公司名（部分会话只显示岗位）时，才允许用较长的岗位片段（≥8 字）
    const frag = norm(title);
    if (!h.includes(co) && frag.length >= 8) {
      for (let i = 0; i + 8 <= frag.length; i++) {
        if (h.includes(frag.slice(i, i + 8))) return true;
      }
    }
    return false;
  }

  // 连标题元素都找不到（DOM 变了）：退回整块文本，但只认可公司名，不认岗位短片段
  const box = (await safeEval(cdp, `(document.querySelector('.chat-conversation') || {}).innerText || ''`)) || '';
  const b = stripCompanyNoise(box);
  return !!co && co.length >= 2 && b.includes(co);
}

async function clickStartChat(cdp, company, title) {
  const clickers = [
    () => safeEval(cdp, `(() => { const b = document.querySelector('.btn-startchat'); if (!b) return false; b.click(); return true; })()`),
    () => dispatchClick(cdp, '.btn-startchat'),
    () => coordClick(cdp, '.btn-startchat'),
  ];
  for (const doClick of clickers) {
    try { await doClick(); } catch { }
    for (let i = 0; i < 12; i++) {
      await sleep(500);
      if (!(await safeEval(cdp, `!!document.querySelector('#chat-input')`))) continue;
      if (await conversationMatches(cdp, company, title)) return true;
    }
  }
  return false;
}

/**
 * 在会话列表里按公司名找并点开。
 * 修掉了裸 includes 的错位风险：给候选打分，完全相等 > 前缀相等 > 包含，
 * 并且把「有多个同分候选」当成需要提示的情况打出来，不再静默取第一个。
 */
function clickFriendScript(company, title) {
  return `(() => {
    const want = ${JSON.stringify(company)};
    const wantTitle = ${JSON.stringify(String(title || ''))};
    const NORM = /[\\s\\u00a0\\u200b-\\u200d\\ufeff\\u3000]/g;
    const clean = (s) => String(s || '').replace(/\\.\\.\\.|…/g, '').replace(NORM, '');
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
      cand.push({ el, score, text: text.slice(0, 60) });
    }
    if (!cand.length) return JSON.stringify({ ok: false, why: 'notfound' });
    cand.sort((a, b) => b.score - a.score);
    const best = cand[0];
    best.el.scrollIntoView({ block: 'center' });
    for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'])
      best.el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window, button: 0 }));
    return JSON.stringify({
      ok: true, score: best.score, text: best.text,
      ambiguous: cand.length > 1 && cand[1].score === best.score,
      candidates: cand.length,
    });
  })()`;
}

async function openChatByCompany(cdp, company, title) {
  const co = stripCompanyNoise(company);
  try { await cdp.send('Page.navigate', { url: CHAT_URL }, 30000); } catch { }
  const listed = await waitFor(cdp, `document.querySelectorAll('.friend-content').length > 0`, 25, 800);
  if (!listed) return false;

  const clickByCompany = async () => {
    const raw = await safeEval(cdp, clickFriendScript(co, title));
    if (!raw) return false;
    let r;
    try { r = JSON.parse(raw); } catch { return false; }
    if (!r.ok) return false;
    if (r.ambiguous) L(`    ⚠ 有 ${r.candidates} 个都像同一家，取分最高的：${r.text}`);
    if (await waitFor(cdp, `!!document.querySelector('#chat-input')`, 12, 500)) {
      return await conversationMatches(cdp, co, title);
    }
    return false;
  };

  // 会话列表是虚拟列表，靠下的会话不在 DOM 里 —— 先用左上角搜索框定位
  const trySearch = async () => {
    const ok = await safeEval(cdp, `(() => {
      const inp = document.querySelector('.boss-search-input, input[placeholder*="搜索"]');
      if (!inp) return false;
      inp.focus(); inp.value = '';
      return true;
    })()`);
    if (!ok) return false;
    await cdp.send('Input.insertText', { text: co });
    await sleep(1800);
    return await clickByCompany();
  };

  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt === 1) {
      if (await trySearch()) return true;
    }
    if (await clickByCompany()) return true;
    await sleep(1200);
  }
  return false;
}

// ---------------------------------------------------------------- 日志（三态）
function readLog() {
  const out = [];
  if (!fs.existsSync(LOG)) return out;
  for (const line of fs.readFileSync(LOG, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try { out.push(JSON.parse(line)); } catch { }
  }
  return out;
}

/**
 * 'sent' | 'failed' | 'skipped'；兼容老日志只有 ok 布尔的情况。
 *
 * 兼容要点：老日志把「空会话 / 已在会话中 / 岗位下线 / 预先跳过」都写成了 ok:false。
 * 这些东西业务上不是失败，若一律按 failed 处理，它们会被「今日失败过就跳过」永久节流。
 * 所以这里按 reason 把它们重新归成 skipped。
 */
const SKIP_REASONS = new Set(['empty-chat', 'already-chatted', 'job-closed', 'pre-skip']);

export function recStatus(r) {
  if (r.status === 'sent' || r.status === 'failed' || r.status === 'skipped') return r.status;
  if (r.ok) return 'sent';
  if (r.reason && SKIP_REASONS.has(r.reason)) return 'skipped';
  return 'failed';
}

function recIsToday(r) {
  if (r.localDay) return r.localDay === todayKey();
  const t = r.time ? new Date(r.time) : null;
  return !!t && t.toDateString() === new Date().toDateString();
}

/**
 * 唯一日志出口。status 必填，reason 必填。
 * 旧代码有五类失败只改内存计数器不落盘，导致 skip 集合永远不知道它们发生过。
 */
function logRec(rec) {
  try {
    fs.mkdirSync(path.dirname(LOG), { recursive: true });
    fs.appendFileSync(LOG, JSON.stringify({
      time: new Date().toISOString(),
      localDay: todayKey(),
      status: 'failed',
      ...rec,
    }) + '\n');
  } catch (e) {
    L('  ⚠ 写日志失败：' + e.message);
  }
}

const logSent = (company, title, extra) =>
  logRec({ company, title, status: 'sent', ok: true, reason: 'ok', ...extra });
const logFail = (company, title, reason, extra) =>
  logRec({ company, title, status: 'failed', ok: false, reason, ...extra });
const logSkip = (company, title, reason, extra) =>
  logRec({ company, title, status: 'skipped', ok: false, reason, ...extra });

/** 读日志统计：今天成功数、历史成功、今天明确失败、今天跳过 */
export function sentToday() {
  const sentTodayCo = new Set();
  const allSent = new Set();
  const failedToday = new Set();
  const skippedToday = new Set();
  let count = 0;

  for (const r of readLog()) {
    const name = stripCompanyNoise(r.company);
    if (!name) continue;
    const st = recStatus(r);
    const isToday = recIsToday(r);
    if (st === 'sent') {
      allSent.add(name);
      if (isToday) { count++; sentTodayCo.add(name); }
    } else if (isToday && st === 'failed') {
      failedToday.add(name);
    } else if (isToday && st === 'skipped') {
      skippedToday.add(name);
    }
  }
  return { count, companies: sentTodayCo, failedToday, skippedToday, allSent };
}

export function loadSkip() {
  const set = new Set();
  if (SKIP_FILE) {
    try {
      const data = JSON.parse(fs.readFileSync(SKIP_FILE, 'utf8'));
      const arr = Array.isArray(data) ? data : (data.friends || data.jobs || []);
      for (const it of arr) {
        const name = it.brandName || it.company || it.brand;
        if (name) set.add(stripCompanyNoise(name));
      }
    } catch (e) {
      L(`⚠ --skip 文件读不了（${SKIP_FILE}）：${e.message}，忽略`);
    }
  }

  const done = sentToday();
  for (const n of done.allSent) set.add(n);       // 历史上发过的，不重复打扰
  for (const n of done.companies) set.add(n);     // 今天发过的
  for (const n of done.failedToday) set.add(n);   // 今天明确失败的，当日先不再试（明天自动解除）

  // 关键修正：今天「跳过」的（空会话 / 已在会话中）不进 skip 集合。
  // 旧实现把它们也记成 ok:false 并永久拉黑，手工修好后重跑照样被跳过。
  return set;
}

// ---------------------------------------------------------------- 进度 / 队尾重试
function loadProgress() {
  try {
    const p = JSON.parse(fs.readFileSync(PROGRESS, 'utf8'));
    if (p && p.queue === QUEUE && p.failed && typeof p.failed === 'object') {
      return { lastIndex: 0, runs: 0, ...p };
    }
  } catch { }
  return { queue: QUEUE, updatedAt: null, lastIndex: 0, runs: 0, failed: {} };
}

function saveProgress(p) {
  try {
    fs.mkdirSync(path.dirname(PROGRESS), { recursive: true });
    p.updatedAt = new Date().toISOString();
    fs.writeFileSync(PROGRESS, JSON.stringify(p, null, 2));
  } catch (e) {
    L('  ⚠ 写进度失败：' + e.message);
  }
}

function markFailed(progress, job, reason) {
  const k = job.url;
  const cur = progress.failed[k] || { count: 0 };
  progress.failed[k] = {
    count: cur.count + 1, reason,
    company: job.company, at: new Date().toISOString(),
  };
}

/**
 * 把「历史失败过」的岗位整体挪到队尾。
 * 这是 P0-3 的核心：旧实现失败项永远堵在队首，每天开局先撞同一批。
 */
export function orderJobs(jobs, progress) {
  const bad = new Set(Object.keys((progress && progress.failed) || {}));
  const fresh = [];
  const retry = [];
  for (const j of jobs) (bad.has(j.url) ? retry : fresh).push(j);
  return [...fresh, ...retry];
}

async function checkLogin(cdp) {
  const probe = async () => {
    const raw = await safeEval(cdp, `fetch('/wapi/zpuser/wap/getUserInfo.json', { credentials: 'include' })
      .then(r => r.text()).catch(() => '')`);
    if (!raw) return false;
    try {
      const j = JSON.parse(raw);
      return j.code === 0 && !!(j.zpData && j.zpData.userId);
    } catch { return false; }
  };

  if (await probe()) return true;

  // 常见情况：当前页面是很早打开的，__zp_stoken__ 没刷新。回首页刷新一次再验
  try { await cdp.send('Page.navigate', { url: 'https://www.zhipin.com/' }, 30000); } catch { }
  await sleep(5000);
  if (await probe()) return true;

  // 再试一次聊天页（有些接口在 chat 域下才带全 cookie）
  try { await cdp.send('Page.navigate', { url: 'https://www.zhipin.com/web/geek/chat' }, 30000); } catch { }
  await sleep(6000);
  return await probe();
}

function validateArgs() {
  const unknown = process.argv
    .slice(2)
    .filter((v) => v.startsWith('--') && !KNOWN_ARGS.has(v.slice(2)));
  if (unknown.length) {
    console.error('未知参数：' + unknown.join(' '));
    console.error('可用参数：' + [...KNOWN_ARGS].sort().map((a) => '--' + a).join(' '));
    console.error('注意：不写 --send 就是演练模式（不会发送）。拼错 --send 会被这里拦下。');
    process.exit(2);
  }
}

async function openCdp() {
  if (!(await ensureBrowser())) return null;
  const page = await getPage();
  if (!page) { console.error('没有可用页面'); return null; }
  const cdp = await connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await activate(cdp);
  return cdp;
}

const pace = async () => {
  const w = rand(DELAY_MIN, DELAY_MAX);
  L(`  …等待 ${w.toFixed(0)} 秒`);
  await sleep(w * 1000);
};

/**
 * 在「当前已经打开的会话」里完成：归属校验 → 写输入 → 发送 → 校验 → 记日志。
 *
 * 这是 P0-1 的核心：旧代码先在岗位页建会话、再导航回列表、再重进，
 * 而建出来的正是「0 条消息的空会话」，回来后被 totalMsgs===0 判死，
 * 22 条 empty-chat 全部来自这个自相矛盾的往返。
 * 现在就地写就地发，只有就地失败才退回「从列表重进」。
 */
export async function sendInConversation(cdp, job, company, title, stage, opts = {}) {
  const co = stripCompanyNoise(company);

  if (!(await waitFor(cdp, `!!document.querySelector('#chat-input')`, 10, 400))) {
    logFail(co, title, 'no-input', { url: job.url, stage });
    return { kind: 'failed', reason: 'no-input', stage };
  }

  if (!(await conversationMatches(cdp, co, title))) {
    L('  × 会话归属对不上，不写不发（避免发错人）');
    logFail(co, title, 'mismatch', { url: job.url, stage });
    return { kind: 'failed', reason: 'mismatch', stage };
  }

  const mineBefore = await countMyMessages(cdp);
  if (mineBefore > 0 && !FORCE) {
    logSkip(co, title, 'already-chatted', { url: job.url, stage, mine: mineBefore });
    return { kind: 'skipped', reason: 'already-chatted', stage, label: `会话里已有 ${mineBefore} 条我的消息` };
  }

  const totalMsgs = Number(await safeEval(cdp, `document.querySelectorAll('.chat-conversation .message-item').length`)) || 0;

  // 关键：刚用「立即沟通」建出来的会话，消息数就是 0 —— 这是正常的首次打招呼状态，
  // 必须尝试写入。只有「从消息列表重进」的旧空会话才是编辑器真的激活不了那种，
  // 那时才跳过（旧实现不分这两种情况，于是 41 条 empty-chat 全被弃掉）。
  if (totalMsgs === 0 && opts.allowEmpty !== true) {
    logSkip(co, title, 'empty-chat', { url: job.url, stage });
    return { kind: 'skipped', reason: 'empty-chat', stage, label: '空会话（编辑器写不进字），留给人工处理' };
  }

  let filled = await fillInput(cdp, job.message);
  if (!filled) {
    L('    输入失败，重新打开会话再试一次…');
    await sleep(1200);
    if (await openChatByCompany(cdp, co, title)) filled = await fillInput(cdp, job.message);
  }
  if (!filled) {
    logFail(co, title, 'fill-failed', { url: job.url, stage });
    return { kind: 'failed', reason: 'fill-failed', stage };
  }

  await sleep(rand(300, 700));
  if (!(await waitSendReady(cdp))) L('  …发送按钮一直禁用，仍尝试回车');
  const how = await clickSend(cdp, job.message);
  await sleep(2200);

  const st = await readInputState(cdp);
  const last = await lastMyMessage(cdp);
  const afterMine = await countMyMessages(cdp);
  const cleared = !st.exists || norm(st.text) === '';

  // 三重条件：输入框已清空 + 我方消息条数确实增加了 + 末条含本条话术的中段指纹
  const landed = messageLanded(last, job.message);
  let ok = cleared && afterMine > mineBefore && landed;

  let recall = '';
  if (ok && !(await conversationMatches(cdp, co, title))) {
    L('  ⚠ 发完归属对不上，尝试撤回…');
    recall = await recallLastMine(cdp);
    L('  撤回结果：' + recall);
    ok = false;
  }

  const why = ok ? ''
    : !cleared ? 'input-not-cleared'
      : afterMine <= mineBefore ? 'no-new-message'
        : !landed ? 'content-mismatch'
          : recall ? 'mismatch-recalled' : 'verify-failed';

  if (ok) {
    logSent(co, title, { url: job.url, message: job.message, how, stage, lastMyMsg: last });
    return { kind: 'sent', reason: 'ok', how, stage };
  }
  L(`  × 校验失败（${why}）：输入框${cleared ? '已清空' : '仍有内容'}，我方消息 ${mineBefore}→${afterMine}，末条=「${String(last).slice(0, 30)}」`);
  logFail(co, title, why, {
    url: job.url, message: job.message, how, stage, lastMyMsg: last, recall,
    mineBefore, afterMine,
  });
  return { kind: 'failed', reason: why, stage };
}

async function main() {
  validateArgs();

  if (arg('help', false)) {
    L('用法：');
    L('  node send_boss.mjs --queue queue_fresh.json --dry-run        演练（只看计划，不开浏览器）');
    L('  node send_boss.mjs --queue queue_fresh.json --check          只验浏览器/页面/登录');
    L('  node send_boss.mjs --queue queue_fresh.json --send --limit 5  真实发送');
    L('可选：--daily-cap 18  --delay-min 50  --delay-max 75  --resume  --force  --start');
    L('      --progress out/progress.json  --log out/send_log.jsonl');
    process.exit(0);
  }

  if (arg('reset', false)) { clearProfileLocks(); L('已清理配置锁文件'); }
  if (arg('selftest', false)) return await selftest();

  if (!fs.existsSync(QUEUE)) {
    console.error('找不到队列文件：' + QUEUE);
    try {
      const dir = path.dirname(path.resolve(QUEUE));
      const cands = fs.readdirSync(dir).filter(f => /^queue.*\.json$/.test(f));
      if (cands.length) console.error('同目录下可选：' + cands.join('  '));
    } catch { }
    process.exit(1);
  }

  const queue = JSON.parse(fs.readFileSync(QUEUE, 'utf8'));
  const rawJobs = (Array.isArray(queue) ? queue : (queue.jobs || [])).filter(j => j && j.url);
  if (!rawJobs.length) { console.error('队列里没有带 url 的岗位：' + QUEUE); process.exit(1); }

  // 队列是否已被脱敏：占位话术一律不发
  const phCount = rawJobs.filter(j => looksPlaceholder(j.message)).length;
  if (phCount && !ALLOW_PLACEHOLDER) {
    if (phCount === rawJobs.length) {
      console.error(`✗ 队列里全部 ${phCount} 条话术都是脱敏占位文本（XX大学 / XX专业 / 张三）。`);
      console.error('  这样发出去，HR 收到的是占位符而不是你的真实信息。');
      console.error('  请先用 build_*.py 重新生成话术；确认无误再发才加 --allow-placeholder。');
      process.exit(1);
    }
    L(`⚠ 队列里有 ${phCount} 条话术含脱敏占位符，这些会被跳过（本次不发送）`);
  }

  const progress = loadProgress();
  progress.runs = (progress.runs || 0) + 1;
  const jobs = orderJobs(rawJobs, progress);
  const failCount = Object.keys(progress.failed || {}).length;
  const skip = loadSkip();

  const dailyCap = Number(arg('daily-cap', 18));
  const today = sentToday();
  const remain = Math.max(0, dailyCap - today.count);
  L(`今日已成功发送 ${today.count} 家（上限 ${dailyCap}，还剩 ${remain}）`);
  if (today.skippedToday.size) L(`今日跳过 ${today.skippedToday.size} 家（不算失败，可重试）`);
  if (failCount) L(`${failCount} 个历史失败岗位已排到队尾`);
  if (SEND && remain === 0) {
    L('今天的量已经到上限，明天再跑。');
    process.exit(0);
  }
  const effectiveLimit = SEND ? Math.min(LIMIT, remain) : LIMIT;
  if (SEND && effectiveLimit < LIMIT) L(`本次实际只发 ${effectiveLimit} 家（受每日上限约束）`);

  let start = 0;
  if (RESUME && progress.lastIndex > 0 && progress.lastIndex < jobs.length) {
    start = progress.lastIndex;
    L(`断点续跑：从第 ${start + 1} 个岗位继续（来自 ${path.basename(PROGRESS)}）`);
  }

  // ---- 只验环境（浏览器 / 页面 / 登录），不发送
  if (CHECK_ONLY) {
    const cdp = await openCdp();
    if (!cdp) process.exit(1);
    const url = await safeEval(cdp, 'location.href');
    const login = await checkLogin(cdp);
    L(`当前页面：${url}`);
    L(login ? '✓ 登录态正常，环境可用' : '✗ 登录态失效，需要人工扫码：node go_login.mjs');
    cdp.close();
    process.exit(login ? 0 : 1);
  }

  L(`队列 ${jobs.length} 个岗位｜本次上限 ${effectiveLimit}｜间隔 ${DELAY_MIN}-${DELAY_MAX}s｜模式：${SEND ? '真实发送' : '演练'}`);

  // ---- 演练：不开浏览器，只列计划
  if (DRY_RUN || !SEND) {
    if (!SEND) L('（演练模式不点按钮、不发送；确认后加 --send）\n');
    let shown = 0;
    for (let i = start; i < jobs.length; i++) {
      if (shown >= effectiveLimit) break;
      const job = jobs[i];
      const company = stripCompanyNoise(job.company);
      if (skip.has(company)) { L(`跳过（已沟通过）：${company}`); continue; }
      const bad = progress.failed[job.url];
      const ph = looksPlaceholder(job.message) ? '　（话术为脱敏占位文本，不会发送）' : '';
      L(`[${shown + 1}] ${company} / ${job.title}${bad ? `　（历史失败 ${bad.count} 次：${bad.reason}）` : ''}${ph}`);
      L('  将发送：' + job.message);
      shown++;
    }
    L(`\n演练结束，共 ${shown} 条待发。确认后加 --send。`);
    process.exit(0);
  }

  const cdp = await openCdp();
  if (!cdp) process.exit(1);

  // 先确认登录态，避免把「掉登录」误判成「限流」
  if (!(await checkLogin(cdp))) {
    L('\n✗ 登录状态已失效（接口返回未登录）。');
    L('  请在 Edge 窗口里重新登录 BOSS，然后加 --resume 从断点继续。');
    saveProgress(progress);
    cdp.close();
    process.exit(1);
  }
  L('登录态正常。');

  fs.mkdirSync(path.dirname(LOG), { recursive: true });
  let sent = 0, skipped = 0, failed = 0, streak = 0, noCreate = 0, placeholderSkipped = 0;

  for (let idx = start; idx < jobs.length; idx++) {
    const job = jobs[idx];
    if (sent >= effectiveLimit) break;
    const company = stripCompanyNoise(job.company);
    const title = job.title;

    progress.lastIndex = idx + 1;

    if (skip.has(company)) { L(`跳过（已沟通过）：${company}`); skipped++; continue; }

    // ---- 0) 占位话术不发
    if (looksPlaceholder(job.message) && !ALLOW_PLACEHOLDER) {
      L(`跳过（话术是脱敏占位文本）：${company}`);
      skipped++; placeholderSkipped++;
      saveProgress(progress);
      continue;
    }

    // ---- 1) 打开岗位页
    const nav = await gotoJob(cdp, job.url);
    if (!nav.ok) {
      if (nav.reason === 'closed') {
        L(`– 岗位已下线：${company}`);
        skipped++;
        logSkip(company, title, 'job-closed', { url: job.url });
        saveProgress(progress);
        continue;
      }
      L(`× 打不开页面（${nav.reason}）：${company}`);
      failed++; streak++;
      logFail(company, title, nav.reason === 'captcha' ? 'captcha' : 'nav-timeout', { url: job.url });
      markFailed(progress, job, nav.reason);
      saveProgress(progress);
      if (nav.reason === 'captcha') { L('触发安全验证，立即停止，等人工处理。'); break; }
      if (streak >= STOP_ON_ERROR) { L('连续失败过多，停止。'); break; }
      continue;
    }

    L(`\n[${sent + 1}] ${company} / ${title}　按钮=${nav.button}`);

    // ---- 2) 建会话；建出来就「就地」写、就地发
    //     旧流程是「建会话 → 导航回列表 → 重进 → 因为 0 条消息弃掉」，
    //     22 条 empty-chat 全出自这个往返。现在就地完成。
    const created = await clickStartChat(cdp, company, title);
    if (created) noCreate = 0; else noCreate++;

    let outcome = null;
    if (created) {
      await sleep(rand(900, 1600));
      // 新建的会话本来就是 0 条消息，允许在这种状态下直接写入
      outcome = await sendInConversation(cdp, job, company, title, 'in-place', { allowEmpty: true });
    }

    // ---- 3) 就地不成 → 回退到「从消息列表重进」
    if (!outcome || outcome.kind === 'failed') {
      if (outcome) L(`  就地未成（${outcome.reason}），改从消息列表重进…`);
      await sleep(rand(1200, 2200));
      const opened = await openChatByCompany(cdp, company, title);
      if (!opened) {
        const reason = created ? 'no-conversation' : 'no-chat';
        L(created ? '  × 消息列表里找不到这家公司' : '  × 建立会话失败');
        logFail(company, title, reason, { url: job.url, stage: 'list' });
        outcome = { kind: 'failed', reason, stage: 'list' };
      } else {
        outcome = await sendInConversation(cdp, job, company, title, 'list');
      }
    }

    // ---- 4) 结算
    if (outcome.kind === 'sent') {
      if (outcome.stage === 'list') L(`  ✓ 已发送（${outcome.how}｜列表路径）`);
      sent++;
      streak = 0;
      delete progress.failed[job.url];
    } else if (outcome.kind === 'skipped') {
      L(`  跳过：${outcome.label || outcome.reason}`);
      skipped++;
      // 注意：skipped 既不累加也不清零 streak。
      // 旧代码在这里 streak=0，让「失败-跳过-失败」交替时连续失败保护永远触发不了。
    } else {
      L(`  × 失败：${outcome.reason}`);
      failed++;
      streak++;
      markFailed(progress, job, outcome.reason);
      if (streak >= STOP_ON_ERROR) {
        L('连续失败过多，停止。');
        saveProgress(progress);
        break;
      }
    }

    if (!created && noCreate >= 3) {
      L('\n⚠ 连续 3 个岗位都建不了会话，大概率是今天的主动沟通配额用完了。');
      L('  建议明天再跑；失败岗位已移到队尾，不会再每天堵在队首。');
      saveProgress(progress);
      break;
    }

    saveProgress(progress);

    // 已经是最后一条（或已到本次上限）就直接收尾，不再空等一个间隔。
    // 旧行为：发完还要再等 40-90 秒才退出，看起来像卡住了。
    if (sent >= effectiveLimit || idx === jobs.length - 1) break;

    await pace();
  }

  saveProgress(progress);
  L(`\n完成：成功 ${sent}｜跳过 ${skipped}｜失败 ${failed}`);
  if (placeholderSkipped) L(`其中 ${placeholderSkipped} 条因话术是脱敏占位文本而跳过（需重新生成话术）`);
  L(`今日累计成功 ${today.count + sent} / 上限 ${dailyCap}`);
  L('日志：' + LOG);
  L('进度：' + PROGRESS);
  cdp.close();
  process.exit(0);
}

// ---------------------------------------------------------------- 自检
async function selftest() {
  L('自检：打开一个已有会话 → 写入 → 读回 → 复原（不会发送）');
  const cdp = await openCdp();
  if (!cdp) process.exit(1);

  const listed = await waitFor(cdp, `document.querySelectorAll('.friend-content').length > 0`, 25, 1000);
  if (!listed) {
    const url = await safeEval(cdp, 'location.href');
    L('× 没看到会话列表，当前页面：' + url);
    cdp.close();
    process.exit(1);
  }

  if (!(await safeEval(cdp, `!!document.querySelector('#chat-input')`))) {
    let opened = false;
    for (let i = 0; i < 4 && !opened; i++) {
      try { await clickSelector(cdp, '.friend-content', i === 0); } catch { }
      opened = await waitFor(cdp, `!!document.querySelector('#chat-input')`, 6, 500);
    }
    if (!opened) { L('× 点不开会话'); cdp.close(); process.exit(1); }
  }
  await sleep(600);

  const orig = await readInput(cdp);
  const sample = '自检文本不会发送' + Date.now().toString().slice(-4);
  const filled = await fillInput(cdp, sample);
  const back = await readInput(cdp);
  const ready = await waitSendReady(cdp);

  // 复原：先清空，原本有草稿就把草稿写回去，避免留下「自检文本」
  let cleared = await clearInput(cdp);
  for (let i = 0; i < 3 && !cleared; i++) { await sleep(500); cleared = await clearInput(cdp); }
  const restore = (cleared && String(orig).trim()) ? orig : '';
  if (restore) await fillInput(cdp, restore);
  const after = await readInput(cdp);
  const restored = norm(after) === norm(restore);

  L('写入成功：', filled);
  L('读回内容：', String(back).slice(0, 40));
  L('发送按钮可用：', ready);
  L('复原结果：', restored ? (restore ? '原草稿已写回' : '已清空') : '⚠ 仍有残留，请手工清空输入框');
  L(filled && String(back).includes('自检文本') && ready && restored ? '\n✓ 输入链路正常' : '\n× 输入链路有问题');
  cdp.close();
  process.exit(0);
}

const ENTRY = process.argv[1] ? path.resolve(process.argv[1]) : '';
const SELF = path.resolve(fileURLToPath(import.meta.url));
if (ENTRY === SELF) {
  main().catch(e => { console.error('运行出错：', e.message); process.exit(1); });
}
