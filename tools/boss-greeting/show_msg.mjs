/**
 * 打印某公司会话里「我发的」完整话术原文（调试用，只读）。
 *
 * 用法：node show_msg.mjs 公司A
 */
import { ensurePage, connectPage, activate, makeEval, CHAT_URL } from './cdp_common.mjs';

const PORT = Number(process.env.CDP_PORT || 9333);
const names = process.argv.slice(2);
if (!names.length) {
  console.error('用法：node show_msg.mjs 公司A [公司B ...]');
  process.exit(1);
}

const page = await ensurePage(PORT, { startUrl: CHAT_URL });
if (!page) {
  console.error(`连不上调试端口 ${PORT}，或开不出标签页。`);
  process.exit(1);
}
const cdp = await connectPage(page.webSocketDebuggerUrl);
await activate(cdp);
await cdp.send('Runtime.enable');
const ev = await makeEval(cdp);

// 在页面上下文发请求，避免 Node 侧缺 __zp_stoken__ 导致空列表
const get = async (p) => {
  const raw = await ev(`fetch(${JSON.stringify(p)}, { credentials: 'include',
    headers: { 'x-requested-with': 'XMLHttpRequest' } }).then(r => r.text()).catch(e => 'ERR:' + e.message)`);
  try { return JSON.parse(String(raw)); } catch { return {}; }
};

const normName = (s) => String(s || '').replace(/\.\.\.|…/g, '').replace(/[\s\u00a0\u3000]/g, '');

const listAll = [];
const seen = new Set();
for (let p = 1; p <= 10; p++) {
  const j = await get(`/wapi/zprelation/friend/getGeekFriendList.json?page=${p}&size=100`);
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
  await new Promise((r) => setTimeout(r, 300));
}

for (const n of names) {
  const want = normName(n);
  const cands = listAll.filter((x) => {
    const b = normName(x.brandName);
    return b === want || b.startsWith(want) || (x.brandName || '').includes(n);
  });
  if (!cands.length) { console.log(`【${n}】列表里没找到`); continue; }

  let printed = 0;
  for (const f of cands.slice(0, 8)) {
    const h = await get(`/wapi/zpchat/geek/historyMsg?bossId=${f.encryptBossId}&maxMsgId=0&c=20&page=1&src=0&securityId=${encodeURIComponent(f.securityId)}`);
    const mine = (h.zpData?.messages || []).map((m) => {
      const b = typeof m.body === 'object' ? m.body : (() => { try { return JSON.parse(m.body); } catch { return {}; } })();
      return { from: m.from?.name || '', text: b.text || '' };
    }).filter((x) => x.text && /XX大学|XX大学/.test(x.text));

    for (const x of mine) {
      console.log(`【${n}】(${f.brandName}) ${x.text}`);
      printed++;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  if (!printed) console.log(`【${n}】（无内容，查了 ${Math.min(cands.length, 8)} 个会话）`);
}

cdp.close();
process.exit(0);
