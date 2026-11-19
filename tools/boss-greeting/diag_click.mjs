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
await sleep(6500);

console.log('落地 URL:', await ev('location.href'));
console.log('标题:', await ev('document.title'));

// 找按钮
const info = await ev(`(() => {
  const btns = [...document.querySelectorAll('a,button,span,div')].filter(e => /^(立即沟通|继续沟通)$/.test((e.innerText||'').trim()));
  if (!btns.length) return 'no-button';
  const b = btns[0];
  const r = b.getBoundingClientRect();
  return JSON.stringify({ text: b.innerText.trim(), tag: b.tagName, cls: b.className, x: Math.round(r.x + r.width/2), y: Math.round(r.y + r.height/2) });
})()`);
console.log('按钮:', info);

let box = null;
try { box = JSON.parse(info); } catch { }

if (box && box.x) {
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 });
  }
  await sleep(4500);
  console.log('点击后 URL:', await ev('location.href'));
  console.log('点击后标题:', await ev('document.title'));
  console.log('页面文本:', String(await ev(`(document.body.innerText||'').slice(0,220)`)).replace(/\n/g, ' | '));
  console.log('是否有验证:', await ev(`/安全验证|请完成验证|滑动|拖动滑块/.test(document.body.innerText||'')`));
}
process.exit(0);
