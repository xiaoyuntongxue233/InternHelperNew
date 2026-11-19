/**
 * 环境自检：只验浏览器 / 页面 / 登录，不发任何消息。
 * 等价于 node send_boss.mjs --check
 */
import { ensurePage, cdpAlive, listPages, connectPage, makeEval } from './cdp_common.mjs';

const PORT = Number(process.env.CDP_PORT || 9333);

if (!(await cdpAlive(PORT))) {
  console.error(`✗ 调试端口 ${PORT} 没开。`);
  console.error(`  起浏览器：msedge.exe --user-data-dir=.boss-profile --remote-debugging-port=${PORT}`);
  process.exit(1);
}

const before = await listPages(PORT);
console.log(`端口 ${PORT} 在线，当前标签页 ${before.length} 个`);

const page = await ensurePage(PORT, { startUrl: 'https://www.zhipin.com/web/geek/chat' });
if (!page) {
  console.error('✗ 开不出标签页');
  process.exit(1);
}
console.log('可用页面：' + page.url);

const cdp = await connectPage(page.webSocketDebuggerUrl);
const ev = await makeEval(cdp);

await cdp.send('Runtime.enable');
const info = await ev(`fetch('/wapi/zpuser/wap/getUserInfo.json',{credentials:'include'}).then(r=>r.text()).catch(()=>'ERR')`);

let code = null, name = '';
try { const j = JSON.parse(String(info)); code = j.code; name = j.zpData?.showName || ''; } catch { }

console.log('登录接口 code：' + code);
if (code === 0) {
  console.log(`\n✓ 环境正常：浏览器在、页面在、登录有效${name ? '（' + name.slice(0, 1) + '**）' : ''}`);
  cdp.close();
  process.exit(0);
}
if (code === 7) {
  console.log('\n✗ 登录态已失效，需要人工扫码：node go_login.mjs');
} else {
  console.log('\n? 登录态未确认，接口返回：' + String(info).slice(0, 160));
}
cdp.close();
process.exit(1);
