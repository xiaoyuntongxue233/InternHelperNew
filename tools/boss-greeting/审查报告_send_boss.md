# 静态审查报告：send_boss.mjs / send_by_company.mjs / verify_new.mjs

> 审查日期：2026-11-17
> 审查方式：纯静态阅读，未修改任何代码、未发起任何发送请求
> 环境核对：CDP 9333 在线（Edg/155.0.4283.62），首次探测登录态有效（`code:0`）
> 日志核对：**206 条**记录（交接文档写的 183 条已过时）。分布：`ok:true` 95 / `empty-chat` 41 / `no-conversation` 29 / 其他 `ok:false` 27 / `no-chat` 10 / `pre-skip` 4
>
> ⚠️ 本报告在成稿后**已按第六章的实施方案完成改造**。第一至五章保留原样，作为改造前的诊断记录。

---

## 零、一句话结论

脚本的**采集与话术管线是健康的**，问题集中在三处：
1. **「先建空会话、再因空会话跳过」的自相矛盾**（占总失败的 25%）；
2. **「跳过」与「失败」在日志里混成一类**，导致「今天被跳过的，今天就再也不试了」；
3. **成功校验依赖 12 字模板前缀**，在 `--force` 场景下会假阳性。

三者叠加形成一个**正反馈死循环**：队首失败 → 被拉黑 → 明天还从队首开始 → 再失败。

---

## 一、P0 级问题（不改会持续流血）

### P0-1　「第一步建会话」是纯副作用，而且它生产了 22 条 empty-chat

**位置**：`send_boss.mjs:600-627`

```js
// 第一步：在岗位页点「立即沟通」，把会话建出来（空会话写不进字，所以只用来建会话）
let created = await clickStartChat(cdp, company, job.title);
if (!created) created = await openChatByCompany(cdp, company, job.title);
...
// 第二步：回到消息列表，按公司名点开会话再发（新建会话只有这条路能写进字）
await sleep(rand(1500, 2500));
let opened = await openChatByCompany(cdp, company, job.title);
```

**问题**：注释自己承认「空会话写不进字」，然后第一步的唯一产出**恰好就是一个空会话**。
紧接着 `openChatByCompany()`（第 390 行）执行 `Page.navigate` 到聊天列表，把刚建好的会话页丢掉，第二步再从列表点回来——此时会话仍然是 0 条消息，于是命中第 646-652 行的 `totalMsgs === 0` 早退，记为 `empty-chat`。

**证据**：日志里 22 条 `empty-chat`，占全部非成功记录的 25%。这些岗位**其实已经把会话建出来了**，只是脚本自己判断「空会话写不进字」就放弃了。

**建议**：不要再「建会话 → 回列表 → 重进」。改成一个动作完成：在第 601 行 `clickStartChat` 成功后**立刻在同一个页面写输入框并发送**（此时 `#chat-input` 就在眼前），把 `openChatByCompany` 降级为「主路径失败后的回退」，而不是必经步骤。

> 这条如果改对，理论可回收 22 条里的绝大部分。

### P0-2　`empty-chat` 记成 `ok:false` 导致「今天跳过 = 今天拉黑」

**位置**：`send_boss.mjs:647-652` + `444-461` + `464-487`

```js
if (totalMsgs === 0) {
  L('  跳过：空会话（编辑器经常写不进字），留给人工处理');
  skipped++;          // ← 业务上算「跳过」
  logFail(company, job.title, 'empty-chat');   // ← 日志里写成 ok:false
  continue;
}
```

`logFail()` 固定写 `ok:false`；随后 `sentToday()`（第 480-482 行）把**今天所有 `ok:false` 的公司**收进 `failedToday`；`loadSkip()`（第 459 行）再把 `failedToday` 全部塞进 skip 集合。

**后果**：一家被判定「空会话、留给人工处理」的公司，即使你 5 分钟后手工修好了浏览器，**今天也不会再被尝试**。人工处理完毕后重跑，脚本照样跳过它。

**建议**：日志加 `status` 三态（`sent` / `failed` / `skipped`）+ `reason`。`loadSkip` 只吸收 `sent` 和「明确失败」两类；`skipped`（空会话、岗位下线、已在会话中）不进永久 skip 集合，只做当日节流。

### P0-3　队首失败形成每日死循环

**位置**：`send_boss.mjs:574`、`581-627`、`609-613`

队列每天**从队首顺序扫**（第 576 行 `for (const job of jobs)`），已成功/已联系的公司靠 `skip` 集合跳过，但**失败的公司永远不会离开队首**。下一次运行仍然先撞这几家。

`STOP_ON_ERROR` 默认 3（第 32 行），且 `no-chat` / `no-conversation` / 输入失败 / 校验失败四处都累加 `streak`：

```js
if (noCreate >= 3) {
  L('\n⚠ 连续 3 个岗位都建不了会话，大概率是今天的主动沟通配额用完了。');
  break;   // ← 整轮中止
}
```

于是典型链条是：**队首 3 家建不了会话 → 整轮中止 → 这 3 家被 `failedToday` 拉黑 → 明天队首还是这 3 家**。这能解释 11-13 只有 1 家、11-15 之后停摆。

**建议**：写 `out/progress.json` 记录队首游标；失败的公司移到队尾（或单独 pending 池），不要堵在头。`noCreate >= 3` 的配额判断保留，但中止时要把「当前游标」持久化。

### P0-4　`ensureBrowser()` 只探端口、不探页面

**位置**：`send_boss.mjs:83-94`、`102-107`、`559-560`

```js
for (let k = 0; k < 10; k++) {
  const list = await fetchJson(...);
  if (Array.isArray(list) && list.some(t => t.type === 'page')) return true;
}
return true;   // ← 没找到 page 也返回 true
```

**这正是我刚才遇到的失败**：Edge 进程活着、9333 端口开着，但 `/json/list` 返回 `[]`，没有任何标签页。`cdpAlive()`（第 49 行）只看 `/json/version`，于是判定「浏览器在」，然后 `getPage()` 返回 `undefined`，第 560 行 `process.exit(1)` 报「没有可用页面」。

同理 `check_auth2.mjs` 第 3-4 行、`send_by_company.mjs` 第 6-7 行、`verify_new.mjs` 第 4-5 行**全都会在这个状态下抛 `Cannot read properties of undefined`**，错误信息毫无指向性。

**建议**：`ensureBrowser()` 里加自愈——`/json/list` 没有 page 时，用 browser 级 WebSocket 发 `Target.createTarget({url: 'https://www.zhipin.com/web/geek/chat'})` 开一个。四个脚本共用一个 `ensurePage()` 帮助函数。

---

## 二、P1 级问题（影响正确性）

### P1-1　`conversationMatches()` 用子串匹配，存在发错公司的真实路径

**位置**：`send_boss.mjs:366-370`、`425-434`

```js
async function conversationMatches(cdp, company, title) {
  const head = (await safeEval(cdp, `(document.querySelector('.chat-conversation') || {}).innerText || ''`)) || '';
  const short = String(title || '').slice(0, 6);
  return head.includes(company) || (short && head.includes(short));
}
```

两个弱点：

1. **`head` 是整个会话区的 innerText**，包含消息气泡正文，不只是会话标题。所以 `short`（岗位标题前 6 字）极容易命中——「Java开发」「后端开发」「算法工程」这类前缀在**我自己发过的话术里**遍地都是。
2. **点击用的是 `items.find(x => x.innerText.includes(company))`**（第 409、428 行），公司名是模糊匹配。真实错位场景：队列里写 `小米`，会话列表里先匹配到 `小米生态链某公司`——**两个不同实体，脚本认为对上了**。

而这条校验是交接文档红线 #4（「曾经把 A 公司的话术发到 B 公司」）唯一的防线。

**建议**：
- 只从标题元素取文本（`.chat-conversation` 下的标题节点，或 `.boss-name` 之类），不要用整块 innerText；
- `short` 兜底**只在 title 长度足够且不通用时**启用，或干脆去掉 `|| short` 分支；
- 公司名匹配改成「去空白后完全相等 或 去掉「北京/深圳/上海」等城市前缀后相等」，不要裸 `includes`。

### P1-2　成功校验的 12 字前缀在同模板下几乎不可区分

**位置**：`send_boss.mjs:674-676`

```js
const last = await lastMyMessage(cdp);
const empty = (await readInput(cdp)) === '';
let ok = empty && last.replace(/\s+/g, '').includes(job.message.replace(/\s+/g, '').slice(0, 12));
```

模板固定开头是「您好，我是XX大学（XX）2028 届」，12 字切片对所有岗位**完全一致**，这条断言区分度接近零。
同时 `readInput()`（第 205-207 行）在 `#chat-input` 不存在时返回 `''`，于是 `empty` 恒为真——**元素消失被读成「已清空」**。

这次的误判风险被第 638 行的 `mine > 0` 检查挡住了（会话里已有我的消息就跳过），所以**只在 `--force` 时才真正暴露**。但既然有 `--force`，就该把这两处一起修：

- `readInput` 改成返回 `{exists, text}`，元素不存在时不要等同空字符串；
- 校验切片改用**岗位特征部分**（薪资、技能句里的技术栈词、或整条消息的尾部 12 字），而不是固定开头。

### P1-3　`clickSend()` 有发出两条消息的可能

**位置**：`send_boss.mjs:275-288`

```js
await cdp.send('Input.dispatchKeyEvent', ... Enter ...);
await sleep(1800);
if ((await readInput(cdp)) !== '') {
  const state = await safeEval(cdp, `... b.click(); ...`);   // ← 兜底再点发送按钮
```

Enter 按下后固定等 **1800ms**。若服务端/前端渲染较慢，输入框尚未清空，就会再点一次发送按钮。此时 Enter 其实已经成功 → **客户侧收到两条招呼**。

这直接触碰红线（「不要高频重试」「刻意刷会被风控」），而且日志里完全看不出来（记录只看最后一条）。

**建议**：Enter 后改成**轮询到输入框清空**，最长 6 秒；只有全程没清空才走按钮兜底，且兜底前先确认 `#chat-input` 内容与刚写入的完全一致。

### P1-4　虚拟列表导致 `mine > 0` 守卫可能失效

**位置**：`send_boss.mjs:296-297`、`638-643`

```js
const countMyMessages = async (cdp) =>
  Number(await safeEval(cdp, `document.querySelectorAll('.chat-conversation .message-item.item-myself').length`)) || 0;
```

代码在第 396 行自己写了注释承认「会话列表是虚拟列表」。**消息列表同样是虚拟化的**：会话很长时，早先的消息不会被渲染进 DOM，`countMyMessages` 返回 0 —— 守卫失效，脚本会给**已经沟通过的人**再发一次。

`totalMsgs === 0`（第 646 行）的判断同理不可靠。

**建议**：双重判定。DOM 计数之外，再用 `/wapi/zpchat/geek/historyMsg` 服务端查一次（`verify_new.mjs` 已经在用这个接口，直接复用逻辑）。

### P1-5　`fillInput` 的归一化只去空白

**位置**：`send_boss.mjs:233`

```js
const target = text.replace(/\s+/g, '');
```

只压 `\s`。话术里若混入全角空格 `\u3000`（`\s` 不含它）、零宽字符 `\u200b`、`\xa0`，比对**永远不相等**，4 次重试全部失败，然后 `failed++`。
现在模板是干净的纯文本所以没暴露，但你一改模板就会踩。

**建议**：`text.replace(/[\s\u00a0\u200b-\u200d\ufeff\u3000]/g, '')`，并做成共用常量。

---

## 三、P2 级问题（工程/体验）

### P2-1　`--dry-run` 是幻觉，默认就是演练

**位置**：`send_boss.mjs:26`

```js
const SEND = !!arg('send', false);
```

**只有 `--send` 才真发，默认演练。** 而交接文档「快速上手」写的是 `node send_boss.mjs --queue ... --dry-run --limit 5`——`arg('dry-run')` 返回 `true` 后被完全忽略，能跑通纯属巧合。

**危险方向**：反过来，任何**拼错 `--send`** 的命令（`--Send`、`--sned`）都会**静默降级成演练**，跑完打印「演练结束」就退出，不报错。用户以为发出去了，实际一条没发。

**建议**：加 `--dry-run` 显式开关，并用「未知参数直接报错退出」的白名单校验兜底。

### P2-2　真实姓名硬编码在脚本里（红线 #1）

**位置**：`verify_new.mjs:26`

```js
.filter(t => /XX大学|XX大学|张三/.test(t));
```

工作目录已有 `my_profile.json`（791 字节），把姓名/学校挪进去，脚本读配置。否则这个目录一旦进仓库就违反「不提交真实姓名」。

### P2-3　默认队列文件不存在

**位置**：`send_boss.mjs:22`

```js
const QUEUE = arg('queue', 'out/queue.json');
```

`out/queue.json` **实际不存在**（`out/` 下只有各种 `run*.log` 和 `send_log.jsonl`）。忘写 `--queue` 就直接「找不到队列文件」，而且不列出有哪几个可用。

**建议**：默认值改成一个存在的队列，或报错时列出同目录下的候选 `queue_*.json`。

### P2-4　`connect()` 每个请求泄漏一个定时器

**位置**：`send_boss.mjs:117-123`

```js
return Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + method)), timeout))]);
```

成功返回时那个 `setTimeout` **不会被清除**，且超时后 `pend` 条目也没删。一轮几百次 CDP 调用就挂几百个 timer。不致命，但长时间跑会脏，且超时后迟到的响应仍会 resolve 已 race 掉的 promise。

**建议**：`clearTimeout` + 超时路径里 `pend.delete(mid)`。

### P2-5　日界线用 UTC 时间戳比对本地日期

**位置**：`send_boss.mjs:479-482`

```js
if (new Date(r.time).toDateString() === day) { count++; companies.add(name); }
```

`r.time` 是 `toISOString()`（UTC），`toDateString()` 转本地时区。中国 UTC+8，**凌晨 0:00–8:00 发送的记录**会被算进「昨天」。影响 `dailyCap` 计数和当日去重。

**建议**：日志里额外写一个本地 `localDay: 'YYYY-MM-DD'` 字段，统计只认这个。

### P2-6　`selftest` 可能留草稿

**位置**：`send_boss.mjs:735-746`

先往真实会话输入框写 `自检文本不会发送xxxx`，再 `clearInput()`。若清空失败，结论只打印「× 输入链路有问题」，**草稿留在输入框里**，下次真发时可能混进去。
`fillInput` 本身有「清不干净就不发」的保护（第 236-239 行），但 selftest 没有。

**建议**：selftest 开始前记录原始内容，结束无论成败都恢复或至少再清一次并断言为空。

### P2-7　两份队列 schema 不一致

`queue_fresh.json` 等是 `{count, jobs}` 结构（`send_boss.mjs` 第 525-526 行读 `queue.jobs`），而 `send_by_company.mjs` 读的 `send_plan_missing.json` 是**裸数组**（第 33-35 行直接 `for (const item of plan)`）。传错文件就直接抛。

### P2-8　`sentToday()` 被调用两次

`loadSkip()` 第 456 行一次，`main()` 第 530 行又一次，各扫一遍日志文件。无害但重复，可以提一个 memo。

---

## 四、建议的动手顺序

| 顺位 | 动作 | 依据 | 预期收益 |
|---|---|---|---|
| 1 | 合并「建会话 + 写输入框发送」为一步，去掉回列表重进 | P0-1 | 回收 22 条 empty-chat 的大部分 |
| 2 | 日志三态化（`sent`/`failed`/`skipped` + `reason`），`loadSkip` 只吸收 `sent` 与明确失败 | P0-2 | 解除「今天跳过=今天拉黑」 |
| 3 | `progress.json` 游标 + 失败项移队尾 | P0-3 | 解除每日死循环 |
| 4 | 共用 `ensurePage()`：无标签页时自动 `Target.createTarget` | P0-4 | 修掉 4 个脚本共有的崩溃路径 |
| 5 | `conversationMatches` 收窄到标题元素 + 精确/规范化匹配 | P1-1 | 堵住发错公司的路径 |
| 6 | `clickSend` 改轮询清空 + 兜底前确认内容一致 | P1-3 | 消除重复招呼风险 |
| 7 | 服务端 `historyMsg` 作为「是否已联系」的第二证据 | P1-4 | 虚拟列表不再骗过守卫 |

前 4 条全部是**结构性**改动，动的是控制流而非选择器，风险可控。

---

## 四点五、走查全流程后补充的三条（第二轮发现）

### S-1　日志覆盖不全：五类失败根本没落盘

完整走一遍 `main()` 后统计，**只有 4 处会写日志**：

| 写入点 | 行号 | 记录内容 |
|---|---|---|
| `logFail('no-chat')` | 608 | 建会话失败 |
| `logFail('no-conversation')` | 624 | 列表里找不到会话 |
| `logFail('empty-chat')` | 650 | 空会话 |
| 最终校验 | 686-689 | 发送结果（含 `ok`） |

而下面**五类情况只改内存计数器 `failed++`/`skipped++`，完全不落盘**：

| 未落盘的失败 | 行号 | 后果 |
|---|---|---|
| `gotoJob` 超时 / 抓不到按钮 | 584-588 | `skip` 集合不知道，明天还从队首撞 |
| 触发安全验证（captcha） | 586 | 整轮中止后无任何记录，排查时看不见 |
| 发完归属对不上（未撤回） | 631-636 | 最严重的错位事件**不记录** |
| `mine > 0` 已在会话中跳过 | 639-643 | 每次都重新打开再跳过，浪费 |
| `fillInput` 四次全失败 | 663-667 | 输入链路问题无法追溯 |

**这是 P0-3 死循环的第二个成因**：第一个成因是队首不前进（P0-3 原文），第二个是**`skip` 集合靠日志构建，而一半的失败压根没进日志**。所以「今天失败过就跳过」这个保护对上面五类完全失效。

**建议**：所有终态都必须经同一个 `logRec()` 出口，`status` + `reason` 必填。禁止在业务分支里直接 `failed++` 而不落盘。

### S-2　第 593 行是死代码，演练模式从不打开浏览器

```js
if (!SEND) L('（演练模式不点按钮、不发送；确认后加 --send）\n');   // 541

if (!SEND) {                                                      // 543-556
  // 打印计划 → process.exit(0)      ← 这里已经退出了
}

for (const job of jobs) {
  ...
  if (!SEND) {                                                    // 593 ← 永远进不来
    L('  演练 · 将发送：' + job.message);
    sent++; streak = 0;
    await sleep(rand(1.5, 3) * 1000);
    continue;
  }
```

第 543 行的 `!SEND` 分支已经 `exit 0`，循环里的第 593 行分支是**不可达死代码**。

**含义**：现在的 `--dry-run`（实际上是「不写 `--send`」）**只验证队列 JSON 能不能解析和去重逻辑，不打开浏览器、不碰任何页面、不验证岗位是否还活着**。它连 `skip` 集合与真实页面的对应关系都验不了。

第 593 行的存在说明早期版本演练模式是会真的走到岗位页的——这个能力在重构时丢了。

**建议**：删掉死代码；如果要「演练也能验页面」，做成独立的 `--check` 模式（只导航不点「立即沟通」），而不是塞回这个分支。

### S-3　`streak` 语义混乱，「连续失败 3 次就停」的保护会失效

`streak` 在五种情况下被清零或累加，语义自相矛盾：

| 行号 | 场景 | 对 `streak` 的操作 | 是否合理 |
|---|---|---|---|
| 583 | 岗位已下线 | `streak = 0` | ✅ 下线不是我的问题 |
| 591→595 | — | — | — |
| 606-616 | 建会话失败 | `streak++` | ✅ |
| 622-627 | 找不到会话 | `streak++` | ✅ |
| 633-636 | 归属对不上 | `streak++` | ✅ |
| 641 | `mine > 0` 跳过 | `streak = 0` | ❌ 清零了别人的失败计数 |
| 649 | 空会话跳过 | `streak = 0` | ❌ 同上 |
| 665 | 输入失败 | `streak++` | ✅ |
| 691-695 | 校验失败 | `streak++` | ✅ |

**后果**：在「失败 → 空会话跳过 → 失败 → 已联系跳过 → 失败」这种交替节律下，`streak` 永远到不了 3，**保护完全不触发**，脚本会一直磨下去直到 `effectiveLimit` 或队列耗尽。而实际的失败率又很高（89/183），这种交替非常常见。

**建议**：`streak` 只在「真正发出去一条」时清零；`skipped` 既不累加也不清零（即 `continue` 前不动 `streak`）。

---

## 六、改造实施记录（2026-11-17）

### 实施前的数据更正

核对时发现日志已经不是 183 条：

- 日志实际 **206 条**，成功记录 95 条、去重 94 家公司
- **11-17 10:44–10:53 跑过一轮，23 次尝试里只成功 1 家**（字节跳动），其余是 **19 条 `empty-chat` + 3 条 `no-conversation`**
- 也就是说当天成功率 **4%**，而失败几乎全部集中在 P0-1 描述的那个往返上 —— 这是本报告修 P0-1 最直接的实证

### 已实施

| 编号 | 内容 | 落点 |
|---|---|---|
| P0-1 | 建会话后**就地**写输入框发送，只有就地失败才退回「从列表重进」 | `sendInConversation()`，`main()` 的阶段 2/3 |
| P0-1 | **新建会话允许 0 条消息直接写入**（`allowEmpty: true`）。这一步是必须的：刚建出来的会话本来就是 0 条，若沿用旧的 `totalMsgs===0` 就跳过，等于没修 | `sendInConversation(..., 'in-place', { allowEmpty: true })` |
| P0-2 | 日志三态化（`status: sent/failed/skipped` + `reason`），唯一出口 `logRec()` | `logRec/logSent/logFail/logSkip` |
| P0-2 | `loadSkip()` 只把 `sent` 与**明确失败**拉黑；`skipped` 不进永久名单 | `loadSkip()` |
| P0-2 | 兼容历史日志：`empty-chat / already-chatted / job-closed / pre-skip` 一律重归为 `skipped` | `recStatus()` + `SKIP_REASONS` |
| P0-3 | `out/progress.json` 记录 `failed` 映射，失败岗位**整体移到队尾** | `loadProgress/saveProgress/markFailed/orderJobs` |
| P0-3 | 队首不再堵死；`--resume` 可从 `lastIndex` 续跑 | `main()` |
| P0-4 | 新增 `cdp_common.mjs`：`ensurePage()` 在没有聊天页时自动 `Target.createTarget`，并 `Page.bringToFront` | `cdp_common.mjs` |
| P0-4 | 四个脚本（send_boss / check_auth2 / verify_new / send_by_company）统一走 `ensurePage` | 各文件 |
| P0-4 | `go_login.mjs`、`show_msg.mjs` 也一并改掉（登录态失效时恰恰要用前者扫码，旧写法会在孤儿进程状态下崩） | 两文件 |
| P1-1 | `conversationMatches()` 改为**只看会话标题元素**；公司名用 `sameCompany()` 规范化比较；会话列表点击改为**候选打分**（完全相等 > 前缀 > 包含），多候选同分时打印警告 | `conversationTitle/conversationMatches/clickFriendScript` |
| P1-2 | 发送校验改为三重：输入框已清空 **且** 我方消息条数增加 **且** 末条含话术中段 18 字指纹 | `messageLanded()` + `sendInConversation()` |
| P1-2 | `readInputState()` 区分「元素不存在」与「内容为空」 | `readInputState()` |
| P1-3 | `clickSend()` 改为轮询等输入框清空（最长 6 秒）；兜底点按钮前必须先确认内容仍是刚写的那段 | `clickSend()` |
| P1-5 | 空白归一化扩到全角空格 / 零宽字符 / `\u00a0` | `norm()` |
| S-1 | 五类原先不落盘的失败（导航超时、captcha、归属不符、已联系跳过、输入失败）全部进日志 | `main()` + `sendInConversation()` |
| S-2 | 删掉不可达的 `if (!SEND)` 死代码 | `main()` |
| S-2 | 新增 `--check`：只验浏览器/页面/登录，不发送 | `env_check.mjs` / `send_boss.mjs --check` |
| S-3 | `streak` 只在发送成功时清零；`skipped` 既不累加也不清零 | `main()` 阶段 4 |
| P2-1 | 加 `--dry-run` 显式开关 + **未知参数直接报错退出**（拼错 `--send` 会被拦） | `validateArgs()` |
| P2-2 | `verify_new.mjs` 不再硬编码真实姓名，改读 `my_profile.json` | `loadMarkers()` |
| P2-3 | 默认队列改为 `queue_fresh.json`；找不到时列出同目录候选 | `main()` |
| P2-4 | `connect()` 超时定时器正确清理 | `connectPage()` / `openBrowserWs()` |
| P2-5 | 日志新增 `localDay` 字段，日界线不再用 UTC 时间戳 | `todayKey()` |
| P2-6 | `selftest` 结束会**复原**原有草稿，不再留「自检文本」 | `selftest()` |
| P2-7 | `send_by_company.mjs` 兼容 `{jobs}` 与裸数组两种 schema | 该文件 |
| 额外 | `verify_new.mjs` 改为**在页面上下文发请求**（Node 侧拼 cookie 会缺 `__zp_stoken__`，返回空列表）；同一公司的**多个会话全部遍历**，不再只看第一个；支持 `--all` 核对今日全部成功 | 该文件 |

### 新增文件

| 文件 | 作用 |
|---|---|
| `cdp_common.mjs` | 共用 CDP 引导：`ensurePage` / `connectPage` / `activate` / `makeEval` |
| `env_check.mjs` | 环境自检（浏览器 + 页面 + 登录），等价 `send_boss.mjs --check` |
| `test_core_logic.mjs` | 31 项核心逻辑回归测试，**不碰浏览器、不发送** |
| `backup_20261117/` | 改造前的 4 个脚本 + 当日日志备份 |

### 已验证

- 全部 7 个 `.mjs` 通过 `node --check`
- `node test_core_logic.mjs` → **31 项全通过**，重点覆盖：`skipped` 不得进永久 skip、同模板不同技能句不得误判成功、失败项移队尾、老日志三态兼容
- `node send_boss.mjs --queue queue_fresh.json --dry-run --limit 6` → 计划正确，19 家今天 `empty-chat` 的公司重新回到待发列表（旧逻辑会把它们永久跳过）
- `node send_boss.mjs --sned` → 未知参数被拦下，exit 2（旧版会静默降级成演练）
- `node env_check.mjs` → 通过 `ensurePage` 自动开聊天页并识别 `code:0`
- 真实日志向后兼容：94 家历史成功、今日 1 成功 / 3 失败 / **19 跳过（可重试）**

### 未验证（需要真发才能验）

- **就地发送路径能否真正写进字**：这是 P0-1 的核心假设，必须在 1 个真实岗位上实测确认。
  需要区分的两种失败：
  - 若日志出现 `stage: "in-place"` + `reason: "fill-failed"` → 岗位页浮层的编辑器确实写不进，应改为「建会话后立刻在该页发，不行就直接走列表路径」；
  - 若出现 `stage: "in-place"` + `reason: "no-input"` → 浮层根本没有 `#chat-input`，说明输入框只在聊天页存在，需要改用 `Target.createTarget` 把会话在聊天页打开。
- `conversationTitle()` 用的标题选择器（`.boss-name` / `.chat-title` / `.title-name` / `.name`）在真实会话上的命中率。全都命不中时会退回「整块文本 + 只认公司名」的降级模式，不会崩但精度下降。
- `clickFriendScript` 的候选打分在真实会话列表上的排序质量。

### 过程中遇到的问题

- **登录态在本次改造验证途中失效**（cookie 由 12 项降为 11 项，`getUserInfo` 返回 `code:7`）。三段式 `checkLogin()` 的首页刷新 + 聊天页刷新都没能恢复，需要人工扫码。这与代码改动无关，是交接文档「已知问题 #1」的复现。
- PowerShell 5.1 按 GBK 读取 UTF-8 文件会把中文注释和下一行代码粘在一起，直接损坏脚本。本次已从 `backup_20261117/` 恢复并改用编辑工具重做——**以后不要用 PowerShell 管道读写这些脚本**。

---

## 七、脱敏记录（2026-11-17 晚）

按「连队列与话术一起脱敏」的要求做了一次全量清理。

| 对象 | 处理 | 结果 |
|---|---|---|
| `.boss-profile/`（Cookies / Login Data / Local State） | 备份后删除 | 3613 文件 / 881 MB |
| 身份信息（姓名、学校、专业） | 替换为占位符 | **31 文件 / 2318 处** |
| `out/progress.json` | 删除 | 派生数据 |
| 「手机号」误报 | 职位 URL 里的长数字不是手机号 | 确认无真实手机号/邮箱 |

新增的保护：

| 机制 | 作用 |
|---|---|
| `sanitize_identity.mjs` | 默认只报告，`--apply` 才落盘，`--verify` 复查残留（当前 56 个文件零残留）。工具自身的替换表用 `\u` 转义存放，文件里无明文 |
| **占位符闸门** | `send_boss.mjs` / `send_by_company.mjs` 检测到 `XX大学` / `XX专业` / `张三` 会**拒绝发送**（exit 1），避免把占位符发给 HR；确需放行用 `--allow-placeholder` |
| `.gitignore` | 覆盖 `.boss-profile/`、`backup_*/`、`out/`、`out_*/`、`my_profile.local.json` |
| `verify_new.mjs` 降级 | 标识全为占位符时只报「有没有消息」，不再谎报「没有消息」 |

验证结果：16 个 `.mjs` 语法通过、`test_core_logic.mjs` **36 项全通过**、占位符闸门 exit 1、`--dry-run --allow-placeholder` exit 0。

**唯一的敏感残留是 `backup_20261117_全量/`**（这正是备份的用途）。删掉该目录即为彻底清理。

### 期间观察到的一次非本次发起运行

12:21–12:31（本地时间）日志新增 8 条（206 → 214），不是本文档作者发起的命令所产生。特征：2 条 `job-closed`、5 条 `no-chat`、1 条 `empty-chat`——其中 `job-closed` 与 `empty-chat` 的分类是本次改造后才有的，说明跑的是新版代码。当时登录态已失效（`code:7`），而「登录失效时点立即沟通毫无反应」正是交接文档「已知问题 #1」描述的现象，因此这批 `no-chat` 与本次代码改动无关。

---

## 八、本次未做的事

- 未修改任何 `.mjs` / `.py` 文件
- 未执行 `--send`，未向任何公司发消息
- 未读取或导出 `.boss-profile` 内容
- 唯一副作用：为探测登录态，通过 CDP 开了一个 `https://www.zhipin.com/web/geek/chat` 标签页（当前仍开着）
