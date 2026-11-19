/**
 * cdp_common.mjs —— 四个脚本共用的 CDP 引导
 *
 * 解决的核心问题：Edge 进程活着、9333 端口开着，但 /json/list 返回 []（没有任何标签页）。
 * 旧代码直接 `page.webSocketDebuggerUrl` 就崩在这里，错误信息毫无指向性。
 * 现在统一走 ensurePage()：没有页面就自己开一个。
 */

export const CHAT_URL = 'https://www.zhipin.com/web/geek/chat';
export const HOME_URL = 'https://www.zhipin.com/';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function cdpAlive(port = 9333, ms = 2500) {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(ms) });
    return r.ok;
  } catch { return false; }
}

export async function listPages(port = 9333, ms = 4000) {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(ms) });
    const j = await r.json();
    return Array.isArray(j) ? j : [];
  } catch { return []; }
}

/** 挑一个可用的 page target：优先聊天页，其次任意 zhipin 页，最后任意 page */
export function pickPage(list, preferChat = true) {
  if (!Array.isArray(list) || !list.length) return null;
  if (preferChat) {
    return list.find((t) => t.type === 'page' && t.url.includes('/web/geek/chat'))
      || list.find((t) => t.type === 'page' && t.url.includes('zhipin'))
      || list.find((t) => t.type === 'page')
      || null;
  }
  return list.find((t) => t.type === 'page') || null;
}

/** 连浏览器级 WebSocket（只有它能发 Target.* 命令） */
function openBrowserWs(wsUrl, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    let ws;
    try { ws = new WebSocket(wsUrl); } catch (e) { reject(e); return; }
    const pend = new Map();
    let id = 0;
    let opened = false;
    const timer = setTimeout(() => {
      if (!opened) { try { ws.close(); } catch { } reject(new Error('连不上浏览器调试端口')); }
    }, timeoutMs);

    ws.onopen = () => {
      opened = true;
      clearTimeout(timer);
      resolve({
        send(method, params = {}, t = 15000) {
          const mid = ++id;
          try { ws.send(JSON.stringify({ id: mid, method, params })); }
          catch { return Promise.reject(new Error('浏览器连接已断开')); }
          let timer2;
          const guard = new Promise((_, rej) => {
            timer2 = setTimeout(() => { pend.delete(mid); rej(new Error('timeout ' + method)); }, t);
          });
          const p = new Promise((res, rej) => pend.set(mid, { res, rej, timer2 }));
          return Promise.race([p, guard]);
        },
        close() { try { ws.close(); } catch { } },
      });
    };
    ws.onerror = () => {
      if (!opened) { clearTimeout(timer); reject(new Error('浏览器调试连接失败')); }
    };
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id && pend.has(m.id)) {
        const { res, rej, timer2 } = pend.get(m.id);
        clearTimeout(timer2);
        pend.delete(m.id);
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
      }
    };
  });
}

async function createTarget(port, url, background = false) {
  const r = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(4000) });
  const j = await r.json();
  if (!j.webSocketDebuggerUrl) throw new Error('/json/version 里没有 webSocketDebuggerUrl');
  const ws = await openBrowserWs(j.webSocketDebuggerUrl);
  try {
    return await ws.send('Target.createTarget', { url, background });
  } finally {
    ws.close();
  }
}

/**
 * 拿到一个可用的 page 对象。
 *
 * 策略（按顺序）：
 *   1) 已有聊天页 → 直接用
 *   2) 没有聊天页 → Target.createTarget 开一个（哪怕已经开着职位详情页也一样，
 *      因为下面所有选择器都假设页面是聊天页）
 *   3) 实在开不出来 → 退回到任意 page
 *
 * @returns {Promise<object|null>} null 表示端口不通或页面开不出来
 */
export async function ensurePage(port = 9333, opts = {}) {
  const { startUrl = CHAT_URL, tries = 12, gap = 1000, quiet = true } = opts;

  if (!(await cdpAlive(port))) return null;

  const findChat = (list) => list.find((t) => t.type === 'page' && t.url.includes('/web/geek/chat')) || null;

  let list = await listPages(port);
  let page = findChat(list);
  if (page) return page;

  if (!quiet) console.log('没有聊天页，自动新建一个…');
  try { await createTarget(port, startUrl, false); } catch (e) {
    if (!quiet) console.log('  新建标签页失败：' + e.message);
  }

  for (let i = 0; i < tries; i++) {
    await sleep(gap);
    list = await listPages(port);
    page = findChat(list);
    if (page) return page;
  }

  return pickPage(list, false);
}

/** 把标签页提到前台，避免后台标签被浏览器节流导致 evaluate 超时 */
export async function activate(cdp) {
  try { await cdp.send('Page.bringToFront', {}, 5000); return true; }
  catch { return false; }
}

/**
 * 取页面并连上 page 级 WebSocket，返回最小 CDP 客户端。
 * 供 check_auth2 / verify_new / send_by_company 这类短脚本用。
 */
export function connectPage(wsUrl, timeoutMs = 12000) {
  return new Promise((resolve, reject) => {
    let ws;
    try { ws = new WebSocket(wsUrl); } catch (e) { reject(e); return; }
    const pend = new Map();
    let id = 0;
    let opened = false;
    const timer = setTimeout(() => {
      if (!opened) { try { ws.close(); } catch { } reject(new Error('连不上调试端口（页面可能已关闭）')); }
    }, timeoutMs);

    ws.onopen = () => {
      opened = true;
      clearTimeout(timer);
      resolve({
        send(method, params = {}, t = 25000) {
          const mid = ++id;
          try { ws.send(JSON.stringify({ id: mid, method, params })); }
          catch { return Promise.reject(new Error('浏览器连接已断开')); }
          let timer2;
          const guard = new Promise((_, rej) => {
            timer2 = setTimeout(() => { pend.delete(mid); rej(new Error('timeout ' + method)); }, t);
          });
          const p = new Promise((res, rej) => pend.set(mid, { res, rej, timer2 }));
          return Promise.race([p, guard]);
        },
        close() { try { ws.close(); } catch { } },
      });
    };
    ws.onerror = () => {
      if (!opened) { clearTimeout(timer); reject(new Error('浏览器调试连接失败')); }
    };
    ws.onmessage = (ev) => {
      let m;
      try { m = JSON.parse(ev.data); } catch { return; }
      if (m.id && pend.has(m.id)) {
        const { res, rej, timer2 } = pend.get(m.id);
        clearTimeout(timer2);
        pend.delete(m.id);
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
      }
    };
  });
}

/** Runtime.evaluate 的薄封装，失败抛错 */
export async function makeEval(cdp) {
  return async (expr, timeout = 20000) => {
    const r = await cdp.send('Runtime.evaluate',
      { expression: expr, awaitPromise: true, returnByValue: true }, timeout);
    if (r && r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'evaluate error');
    return r && r.result ? r.result.value : undefined;
  };
}
