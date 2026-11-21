# AI 岗位信息自动填充：最小版实施清单

> 决策（用户确认）：① 用户粘贴文本与抓取脚本两条输入路径都要做；② 回填范围含技能标签；③ 先做最小版。
> **实施状态：已实现，版本升级为 v0.12.0（2026-11-21）。**
> 本轮确认：版本号升级；脚本路径先走零改动方案；接受"溯源校验导致部分字段留空需手填"的安全取舍；保留脚本爬取客户网站获得职位信息的可能性。

---

## 一、最小版范围界定

**做**：用户粘贴页面文本 → AI 抽取 → 确定性校验 → 回填表单（含技能标签）→ 人工确认后保存。
**做**：为抓取脚本路径预留输入接口（本期即支持，复用现成 `import` 体系，见第五节）。
**不做**：后端从 URL 直接抓取网页（反爬 + 合规风险）；AI 结果直接入库；批量并发抽取。

本最小版的产出是**一个接口 + 一个前端按钮**：AI 只返回字段草稿，不触发 `create()`/`update()`，用户核对后走原有保存流程。

---

## 二、数据流（四步）

1. 前端把清洗后的页面文本 POST 到抽取接口；
2. 后端调 DeepSeek，用固定 system prompt 要求输出严格 JSON（白名单字段）；
3. 后端做确定性校验：字段白名单、事实溯源、数值 clamp、技能白名单；
4. 返回字段草稿，前端填入各 input，用户核对后保存。

---

## 三、后端改动（4 个文件）

### 3.1 `AiGreetingClient.java`：新增抽取能力

- 新增常量 `EXTRACT_SYSTEM_PROMPT`：要求模型只做信息抽取、不得推断；输出严格 JSON，字段限定为
  `company / title / rawLocation / salaryText / monthsText / daysText / jdText / skills` 八个字符串字段；
  明确"找不到的字段输出空字符串，不要猜测"。
- 新增 `public AiResult extract(String pageText)`：返回 JSON 字符串 + token 用量。
  - 复用现有 `SKELETON_SYSTEM_PROMPT` 那套调用流程。建议**顺手抽一个私有 `chat(system, user, temperature)`**（`createFree`/`polish`/`extract` 共用），减少三处重复。
  - `temperature` 用 0.1（抽取任务要稳定）。
  - 输入先截断到安全长度（如 6000 字符），防止长页面 token 失控。
  - 提取 content 用现有 `extractMessage` 的同类逻辑（JSON 解析 → 取 message 字段）——注意抽取返回的是**整个 JSON 对象**，需新增一个 `contentOf(response)` 辅助取 `choices[0].message.content`，与 `extractMessage` 区分。

### 3.2 新建 `JobExtraction.java`（服务类，核心校验）

`public record ExtractedJob(...)` 承载结果；`extract(String pageText)` 返回 `Result(ExtractedJob, List<String> warnings)`。

**校验四道关**（这是本功能的安全核心，务必全部实现）：

1. **字段白名单**：模型输出只允许八个约定字段；多余字段丢弃并记 warning（防模型塞入 `status`/`id` 等）。
2. **事实溯源**：每个非空字段值必须能在 `(清洗后的) pageText` 中找到依据——
   - 字符串字段：原文 `contains`（忽略大小写/空白）；找不到 → 清空并 warning"该字段未在页面找到，请核对"。
   - 数字字段（`months`/`days`）：抽取文本里必须含该数字；找不到 → 清空。
   - 这一步直接复用 `GreetingValidator` 的"输出值必须有输入依据"思路，杜绝 AI 编造公司名/技能。
3. **数值 clamp**：`months` 收敛到 2–12、`days` 收敛到 1–7；超界按 clamp 并 warning（与 `JobService` 既有边界一致）。薪资文本交给现成 `MetaParser.parseDailyRange` 解析，AI 不直接吐数字。
4. **技能白名单**：模型给出的技能按逗号切分后，逐项检查是否在 pageText 出现；不在的丢弃并 warning。技能字段落到 `Job.skills`（逗号分隔字符串，与 `profile.html` 的约定一致）。

**成本控制**：调用前 `quota.estimateCost(prompt)` → `quota.reserve`；成功后 `quota.charge(jobId=null, ...)`，失败 `release`。BYO 模式自动跳过平台额度。

### 3.3 新建 `JobExtractController.java`

- `POST /api/jobs/extract`，body `{"pageText":"..."}`，返回 `{company,title,rawLocation,salaryText,salaryType,salaryMin,salaryMax,months,days,jdText,skills,warnings}`。
- 空文本 → 400；模型未配置 → 明确提示"请先在设置页配置模型"；额度不足 → 按现有降级策略返回 warning + 空草稿（不阻塞用户手动填写）。
- 接口**只返回草稿，不落库**。

### 3.4 `application.yml`（可选）

新增 `greeting.extract.max-chars: 6000`、`greeting.extract.max-items: 30`（技能项上限），便于调参。

---

## 四、前端改动（`form.html`，1 个文件）

1. 页面顶部（"手动录入一个岗位"标题旁）加一个折叠区"从页面文本自动填充"，内含 `<textarea id="pageText">` 和按钮"AI 识别并填充"。
2. 按钮点击 → `POST /api/jobs/extract` → 把返回的字段填入现有 input：
   - `company/title/rawLocation/jdText/skills` 直接填；
   - `salaryText` 经前端解析（或后端直接返回 `salaryMin/salaryMax/salaryType`）填薪资框并切换日薪/月薪分段；
   - `months/days` 填数字框。
3. `warnings` 用现有的 `.msg` 区域展示（黄色提示），**不覆盖已填字段**——只填 AI 有把握的字段。
4. 复用 form.html 现有的 `esc()` 做展示转义；沿用 v0.11 的事件委托思路避免重渲染问题（本页无重渲染，普通绑定即可）。
5. 文本发送前做轻量清洗：去 `<script>/<style>` 标签、压缩空白、截断到 max-chars——可用正则，不引第三方库。

---

## 五、抓取脚本路径（本期采用 A：系统侧零改动）

用户选择"先进行零改动的操作"，因此**不改 `ImportService`、不引入 `mode=extract` 分支**。抓取脚本继续负责爬取客户提供的网站，落地方式有两种：

1. 脚本侧自行整理字段，直接写标准 `{"jobs":[...]}` 到 `import-drop/`（原有方式，零改动）；
2. 脚本调用新增的 `POST /api/jobs/extract` 拿到字段草稿，合并 `warnings` 后组装标准 JSON 再投放——同样不改系统导入链路。

两种都保留了"脚本爬取客户网站 → 系统入库"的可能性；区别只是抽取发生在脚本侧还是调用系统接口。若未来需要系统侧自动识别 `mode=extract`，再作为第二期单独做成本控制。

---

## 六、单测（新增 1 个测试类，约 6–8 例）

`JobExtractionTest`（不依赖真实模型，用桩 AI 返回固定 JSON）：
1. 正常抽取：八字段齐全 → 全部回填；
2. 字段白名单：多余字段被丢弃并告警；
3. 事实溯源：编造的公司名/技能被清空并告警；
4. 数值 clamp：months=18 → 12、days=9 → 7；
5. 薪资走 MetaParser：薪资文本被正确折算为日薪区间；
6. 技能白名单：未在页面出现的技能被丢弃；
7. 空文本/未配置模型：返回明确错误；
8. 成本：reserve/charge 调用一次，BYO 不扣平台额度。

---

## 七、风险与边界

- **隐私**：页面文本会发给模型。按钮旁标注"内容将发送给 AI 模型处理"。
- **成本**：长页面 token 高；输入截断 + 单次 reserve 双重约束。
- **准确率**：溯源校验会让 AI"找不到就留空"，用户需手动补少量字段——这是刻意的安全取舍，宁可少填不可错填。
- **与话术架构一致**：本功能不触碰 `GreetingService` 的事实回填机制；技能标签用于岗位库，不自动进入话术。

---

## 八、实施结果（v0.12.0）

- 版本：`pom.xml` 升到 **0.12.0**，构建产物 `intern-helper-0.12.0.jar`。
- 后端：`AiGreetingClient` 抽出共用 `chat()` 并新增 `extract()`；新增 `ExtractionAiClient` / `JobExtractionService` / `JobExtractController`；八字段白名单、事实溯源、月数 2–12 / 天数 1–7 收敛、技能白名单全部落地；薪资数值由 `MetaParser` 折算。
- 前端：`form.html` 加折叠粘贴区、技能标签输入框、AI 回填与黄条告警；不自动保存。
- 单测：新增 `JobExtractionServiceTest` 11 例（正常抽取、白名单、溯源、clamp、日薪、技能、空输入、纯网址拒绝、额度不足、未配置模型、BYO），全套 **109 例通过**。
- 同版本修正：用户实测把网址粘进抽取框导致零字段。前端现识别纯网址并自动填入「网页链接」+ 提示，不发请求、不花额度；后端拒绝纯网址输入并说明原因。该框的输入契约仍是**页面正文 / HTML**，不是 URL。
- v0.12.1 补充：网址不再是"无效输入"的死路——网页新增「按网址识别」按钮，由本机脚本取正文后再走上述抽取链路；异步任务与状态轮询都已实现，服务端仍不直接抓 URL。
- 脚本路径：采用零改动方案，未碰 `ImportService`；README 给出调用抽取接口后写标准 JSON 的用法。
- 取舍：接受"宁可少填不可错填"——AI 编造或页面找不到的字段留空并提示，由用户手填。
