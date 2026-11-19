import fs from 'node:fs';
const PORT = 9333;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let page = null;
for (let i = 0; i < 15 && !page; i++) {
  const tabs = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  page = tabs.find(t => t.type === 'page' && t.url.includes('zhipin')) || tabs.find(t => t.type === 'page');
  if (!page) await sleep(1000);
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

const CITIES = [
  ['广州', '101280100'],
  ['深圳', '101280600'],
  ['东莞', '101281600'],
  ['佛山', '101280800'],
  ['珠海', '101280700'],
  ['中山', '101281700'],
  ['惠州', '101280300'],
];
const QUERIES = ['Java实习', '后端开发实习'];

async function api(city, query, p) {
  const u = `https://www.zhipin.com/wapi/zpgeek/search/joblist.json?scene=1&query=${encodeURIComponent(query)}&city=${city}&page=${p}&pageSize=30`;
  try {
    const r = await fetch(u, { headers: H, signal: AbortSignal.timeout(20000) });
    return JSON.parse(await r.text());
  } catch { return { code: -1 }; }
}

const seen = new Set();
const out = [];
for (const [cname, code] of CITIES) {
  for (const q of QUERIES) {
    let got = 0;
    for (let p = 1; p <= 2; p++) {
      let j = await api(code, q, p);
      let list = (j.zpData && j.zpData.jobList) || [];
      if (!list.length) {
        await sleep(12000);
        j = await api(code, q, p);
        list = (j.zpData && j.zpData.jobList) || [];
      }
      for (const x of list) {
        if (!x.encryptJobId || seen.has(x.encryptJobId)) continue;
        seen.add(x.encryptJobId);
        got++;
        out.push({
          title: x.jobName, company: x.brandName,
          city: (x.cityName || '') + (x.areaDistrict || ''),
          salary: x.salaryDesc,
          url: 'https://www.zhipin.com/job_detail/' + x.encryptJobId + '.html',
          stack: (x.skills || []).join('、'), jd: (x.jobLabels || []).join('、'),
        });
      }
      await sleep(9000);
    }
    console.log(`  ${cname} / ${q} → 累计新增 ${got}`);
  }
}
fs.writeFileSync('gd_jobs.json', JSON.stringify(out, null, 1), 'utf-8');
console.log('广东共抓取', out.length);
process.exit(0);
