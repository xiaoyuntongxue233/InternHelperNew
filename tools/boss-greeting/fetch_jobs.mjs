import fs from 'node:fs';
const PORT = 9333;
let page = null;
for (let i = 0; i < 20 && !page; i++) {
  const tabs = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  page = tabs.find(t => t.type === 'page' && t.url.includes('zhipin')) || tabs.find(t => t.type === 'page');
  if (!page) await new Promise(r => setTimeout(r, 1000));
}
if (!page) { console.error('没有可用页面'); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pend = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pend.has(m.id)) { const p = pend.get(m.id); pend.delete(m.id); p(m); } };
const send = (method, params = {}) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); setTimeout(() => { if (pend.has(i)) { pend.delete(i); res({ error: 't' }); } }, 15000); });
const c = await send('Storage.getCookies', {});
const cookie = (c.result?.cookies || []).filter(x => x.domain.includes('zhipin')).map(x => `${x.name}=${x.value}`).join('; ');
const H = {
  cookie, 'x-requested-with': 'XMLHttpRequest',
  'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0',
  referer: 'https://www.zhipin.com/web/geek/jobs',
};
async function get(p) {
  const r = await fetch('https://www.zhipin.com' + p, { headers: H });
  const t = await r.text();
  try { return JSON.parse(t); } catch { return { code: -1 }; }
}

const queries = JSON.parse(fs.readFileSync('queries.json', 'utf-8'));
const pages = Number(process.argv[3] || 2);
const seen = new Set();
const out = [];
for (const [q, city] of queries) {
  for (let p = 1; p <= pages; p++) {
    const j = await get(`/wapi/zpgeek/search/joblist.json?scene=1&query=${encodeURIComponent(q)}&city=${city}&page=${p}&pageSize=30`);
    const list = (j.zpData && j.zpData.jobList) || [];
    if (!list.length) break;
    for (const x of list) {
      if (!x.encryptJobId || seen.has(x.encryptJobId)) continue;
      seen.add(x.encryptJobId);
      out.push({
        title: x.jobName, company: x.brandName, city: (x.cityName || '') + (x.areaDistrict || ''),
        salary: x.salaryDesc, url: 'https://www.zhipin.com/job_detail/' + x.encryptJobId + '.html',
        stack: (x.skills || []).join('、'), jd: (x.jobLabels || []).join('、'),
      });
    }
    await new Promise(r => setTimeout(r, 400));
  }
}
fs.writeFileSync('fresh_jobs.json', JSON.stringify(out, null, 1), 'utf-8');
console.log('fetched', out.length);
process.exit(0);
