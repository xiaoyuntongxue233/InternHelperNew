/**
 * 查登录态 / 聊天列表接口是否可用。
 * 页面不存在时由 ensurePage 自动新建，不再 undefined 崩。
 */
import { ensurePage, connectPage, makeEval, activate } from './cdp_common.mjs';

const PORT = Number(process.argv[2] || 9333);

const page = await ensurePage(PORT, { startUrl: 'https://www.zhipin.com/web/geek/chat' });
if (!page) {
  console.error(`连不上调试端口 ${PORT}，或开不出标签页。`);
  console.error(`确认 Edge 用 --remote-debugging-port=${PORT} 启动。`);
  process.exit(1);
}

const cdp = await connectPage(page.webSocketDebuggerUrl);
await activate(cdp);
const ev = await makeEval(cdp);

const safe = async (expr) => {
  try { return await ev(expr); } catch (e) { return 'ERR: ' + e.message; }
};

console.log('页面:', await safe('location.href'));
const info = String(await safe(`fetch('/wapi/zpuser/wap/getUserInfo.json',{credentials:'include'}).then(r=>r.text()).catch(()=>'ERR')`));

let code = null;
try { code = JSON.parse(info).code; } catch { }
console.log('登录接口:', info.slice(0, 160));
console.log('聊天列表:', String(await safe(`fetch('/wapi/zprelation/friend/getGeekFriendList.json?page=1&size=5',{credentials:'include','headers':{'x-requested-with':'XMLHttpRequest'}}).then(r=>r.text()).catch(()=>'ERR')`)).slice(0, 120));
console.log('cookie 数:', await safe(`document.cookie.split(';').length`));

cdp.close();

if (code === 0) console.log('\n✓ 登录态正常');
else if (code === 7) console.log('\n✗ 登录态已失效，需要人工扫码（node go_login.mjs）');
else console.log('\n? 登录态未确认（接口没返回预期的 code:0）');

process.exit(code === 0 ? 0 : 1);
