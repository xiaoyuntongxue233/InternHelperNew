import fs from 'node:fs';
const PORT = 9333;
const BATCH = Number(process.argv[2] || 6);   // 本次最多请求几个组合
const GAP = Number(process.argv[3] || 25000); // 每次请求间隔
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const CITIES = [
  ['广州', '101280100'], ['深圳', '101280600'], ['东莞', '101281600'],
  ['佛山', '101280800'], ['珠海', '101280700'], ['中山', '101281700'], ['惠州', '101280300'],
];
const QUERIES = ['Java实习', '后端开发实习'];
const STATE = 'gd_state.json';
const DATA = 'gd_jobs.json';

const state = fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, 'utf-8')) : { done: [] };
const done = new Set(state.done);
const jobs = fs.existsSync(DATA) ? JSON.parse(fs.readFileSync(DATA, 'utf-8')) : [];
const seen = new Set(jobs.map(j => j.url).filter(Boolean));

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

async function api(code, q, p) {
  const u = `https://www.zhipin.com/wapi/zpgeek/search/joblist.json?scene=1&query=${encodeURIComponent(q)}&city=${code}&page=${p}&pageSize=30`;
  try {
    const r = await fetch(u, { headers: H, signal: AbortSignal.timeout(20000) });
    return JSON.parse(await r.text());
  } catch { return { code: -1 }; }
}

let requests = 0;
let added = 0;
outer: for (const [cname, code] of CITIES) {
  for (const q of QUERIES) {
    for (let p = 1; p <= 2; p++) {
      const key = `${code}|${q}|${p}`;
      if (done.has(key)) continue;
      if (requests >= BATCH) break outer;
      requests++;
      let j = await api(code, q, p);
      let list = (j.zpData && j.zpData.jobList) || [];
      if (!list.length) {
        console.log(`  ${cname}/${q}/p${p} 空 → 等 45s 重试`);
        await sleep(45000);
        j = await api(code, q, p);
        list = (j.zpData && j.zpData.jobList) || [];
      }
      if (!list.length) {
        console.log(`  ${cname}/${q}/p${p} 仍为空，留待下次`);
        await sleep(GAP);
        continue;
      }
      for (const x of list) {
        if (!x.encryptJobId) continue;
        const url = 'https://www.zhipin.com/job_detail/' + x.encryptJobId + '.html';
        if (seen.has(url)) continue;
        seen.add(url); added++;
        jobs.push({
          title: x.jobName, company: x.brandName,
          city: (x.cityName || '') + (x.areaDistrict || ''),
          salary: x.salaryDesc, url,
          stack: (x.skills || []).join('、'), jd: (x.jobLabels || []).join('、'),
        });
      }
      done.add(key);
      console.log(`  ${cname}/${q}/p${p} → ${list.length} 条，新增 ${added}`);
      fs.writeFileSync(DATA, JSON.stringify(jobs, null, 1), 'utf-8');
      fs.writeFileSync(STATE, JSON.stringify({ done: [...done] }, null, 1), 'utf-8');
      await sleep(GAP);
    }
  }
}
fs.writeFileSync(DATA, JSON.stringify(jobs, null, 1), 'utf-8');
fs.writeFileSync(STATE, JSON.stringify({ done: [...done] }, null, 1), 'utf-8');
console.log(`本次请求 ${requests} 次，新增 ${added}，累计 ${jobs.length}`);
process.exit(0);
