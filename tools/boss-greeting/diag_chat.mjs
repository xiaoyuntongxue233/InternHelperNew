import fs from 'node:fs';
const PORT = 9333;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const tabs = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = tabs.find(t => t.type === 'page' && t.url.includes('zhipin')) || tabs.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { const p = pend.get(m.id); pend.delete(m.id); p(m); } };
const send = (method, params = {}, t = 25000) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); setTimeout(() => { if (pend.has(i)) { pend.delete(i); res({ error: 't' }); } }, t); });
const ev = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true }, 25000); return r.result?.result?.value; };

const url = process.argv[2];
await send('Page.enable');
await send('Page.navigate', { url });
await sleep(6000);

const box = JSON.parse(await ev(`(() => {
  const b = [...document.querySelectorAll('a,button,span,div')].find(e => /^(立即沟通|继续沟通)$/.test((e.innerText||'').trim()));
  if (!b) return '{}';
  const r = b.getBoundingClientRect();
  return JSON.stringify({ x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2) });
})()`) || '{}');

if (box.x) {
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
  }
  console.log('已点击 立即沟通，等待 8 秒…');
  await sleep(8000);

  const probe = `(() => {
    const el = s => document.querySelector(s);
    return JSON.stringify({
      url: location.href,
      input: !!el('#chat-input'),
      inputTag: el('#chat-input') ? el('#chat-input').tagName + ' / ' + el('#chat-input').className : null,
      conversation: document.querySelectorAll('.chat-conversation').length,
      messages: document.querySelectorAll('.message-item').length,
      friendItems: document.querySelectorAll('.friend-content').length,
      header: (el('.chat-title, .conversation-title, .chat-header') || {}).innerText || null,
      bodyFirst: (document.body.innerText || '').slice(0, 120).replace(/\\n/g, ' | ')
    });
  })()`;
  console.log(await ev(probe));
}
process.exit(0);
