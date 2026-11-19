/**
 * 把页面导航到登录页，方便人工扫码。
 * 页面不存在时由 ensurePage 自动新建，不再 undefined 崩。
 *
 * 用法：node go_login.mjs
 * 扫完后跑：node env_check.mjs
 */
import { ensurePage, connectPage, activate, makeEval } from './cdp_common.mjs';

const PORT = Number(process.env.CDP_PORT || 9333);
const LOGIN_URL = 'https://www.zhipin.com/web/user/?ka=header-login';

const page = await ensurePage(PORT, { startUrl: LOGIN_URL });
if (!page) {
  console.error(`连不上调试端口 ${PORT}，或开不出标签页。`);
  console.error(`先起浏览器：msedge.exe --user-data-dir=.boss-profile --remote-debugging-port=${PORT}`);
  process.exit(1);
}

const cdp = await connectPage(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await activate(cdp);
await cdp.send('Page.navigate', { url: LOGIN_URL }, 30000);
await new Promise((r) => setTimeout(r, 5000));
await activate(cdp);

const ev = await makeEval(cdp);
console.log('页面:', await ev('location.href'));
console.log('\n请在 Edge 窗口里扫码登录，完成后运行：node env_check.mjs');
cdp.close();
process.exit(0);
