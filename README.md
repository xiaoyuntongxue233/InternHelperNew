# 实习投递助手（intern-helper）

一个把自己找实习的过程做成系统的后端项目：**岗位库 + 内置模板与 AI 润色的话术生成 + 投递记录 + 转化统计**。

> 背景：找实习时每天要投几十家，岗位信息散落在表格里、话术每次手写、投了多少/回了多少全靠回忆。这个项目把这些流程沉淀成一套可查询、可统计的系统。

> **免责声明**：本项目仅用于个人求职信息管理与话术草稿生成，不含任何自动发送功能。
> 请勿用于违反目标网站服务条款的行为。

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.3.5 |
| 持久层 | Spring Data JPA（Hibernate 6） |
| 数据库 | MySQL 5.7 |
| 前端 | 原生 HTML + JS（无构建） |
| 构建 | Maven |

## 功能

1. **岗位库**：增删查、**按省 / 市 / 区三级筛选**、关键词搜索、**游标分页**（深翻页恒定耗时）+ **跳转到指定页**
2. **结构化字段**：自动解析实习时长（月）、每周出勤（天）；薪资录入区分**日薪 / 月薪**两种方式，统一折算成**最低日薪 / 最高日薪**（不取平均）
3. **话术生成**：读「个人资料」里的学校/学历/专业/年级/技能生成话术，使用**简洁 / 正式两套内置模板**；技能句按岗位名/技术栈/要求原文匹配；时长与出勤后端硬上限 **12 个月 / 每周 7 天**，同时展示建议「实习最好不超过6个月，每周最好不超过6天。」；可选接入 DeepSeek 在模板草稿上做 AI 润色，失败、校验不过或额度不足自动回退模板
4. **投递记录**：每次投递落一条记录（公司、岗位、实际话术、时间、成功与否），并把岗位状态改成「已联系」
5. **统计**：今日/累计投递数、成功率、岗位库状态分布、各公司投递 Top
6. **数据同步**：JSON 批量导入（按「公司 + 岗位」去重）+ **导入批次留痕** + **定时扫描投放目录自动入库**
7. **岗位类型区分**：按日薪与标题自动识别「实习 / 社招」，列表默认只看实习岗，可切换查看社招
8. **页面**：列表页（筛选、排序、跳页、生成话术、编辑、删除）+ 新增/修改岗位表单页

## 页面

| 地址 | 作用 |
|---|---|
| `/` | 岗位列表：筛选、排序、跳页、生成话术、编辑、删除 |
| `/profile.html` | 个人资料：身份、技能、时长出勤、句式风格 |
| `/settings.html` | 设置：生成引擎、默认模板（简洁/正式/AI自由生成）、模型连接、额度、单价（本机使用） |
| `/form.html` | 新增岗位 |
| `/form.html?id=123` | 修改岗位（带删除按钮，删除前二次确认） |

## 目录结构

```
intern-helper/
├── src/main/java/com/zhou/internhelper/
│   ├── InternHelperApplication.java     # 启动类
│   ├── controller/                      # 接口层：JobController / StatsController / ImportController
│   ├── service/                         # 业务层：GreetingService / JobService / StatsService / ImportService / RegionCatalog
│   ├── repository/                      # 数据访问层：JPA Repository
│   ├── entity/                          # 实体：Job / GreetingRule / ApplicationRecord / ImportBatch
│   ├── config/DataInitializer.java      # 初始化话术规则 + 回填历史岗位省市区
│   └── web/GlobalExceptionHandler.java  # 统一异常处理
├── src/main/resources/
│   ├── application.yml                  # 数据源、JPA、导入目录配置
│   └── static/index.html                # 单页前端
├── sample_jobs.json                     # 导入示例
└── pom.xml
```

## 数据库

五张表，由 JPA 自动建（`ddl-auto: update`）：

| 表 | 说明 |
|---|---|
| `job` | 岗位：公司、岗位名、**省/市/区**、原始地点、薪资、**实习月数/出勤天数/最低日薪/最高日薪**、链接、技术栈、要求原文、状态（NEW/CONTACTED） |
| `greeting_rule` | 话术规则：正则 → 技能句 → 方向名，可改数据库直接生效 |
| `application_record` | 投递记录：jobId、公司、岗位、话术、成功与否、时间 |
| `candidate_profile` | 个人资料（单行）：学校、学历、专业、年级、技能、佐证、可实习时长、每周出勤、句式风格 |
| `ai_usage` | 模型调用用量：本机实例、扣费模式、token 数、金额、提示词版本 |
| `app_setting` | 系统设置（key-value）：引擎、模板、模型连接、单价；数据库值优先于环境变量 |
| `import_batch` | 导入批次：来源、渠道（MANUAL/SCHEDULED）、收到/新增/跳过、时间 |

建库：

```sql
CREATE DATABASE intern_helper DEFAULT CHARACTER SET utf8mb4;
```

## 运行

```bash
# 1. 建库
mysql -uroot -p -e "CREATE DATABASE intern_helper DEFAULT CHARACTER SET utf8mb4;"

# 2. 配置（用环境变量，不要把密码提交进仓库）
export MYSQL_USER=root
export MYSQL_PASSWORD=你的密码
# 可选：候选人信息，不配就用占位值「某大学 / 2028届」
export CANDIDATE_SCHOOL=你的学校
export CANDIDATE_GRADE=2028届

# 可选：AI 润色。默认 RULE 仅用内置模板，不调用模型
export GREETING_ENGINE=RULE              # RULE / AI / HYBRID
export DEEPSEEK_BASE_URL=https://api.deepseek.com
export DEEPSEEK_MODEL=your-model
export PLATFORM_DEEPSEEK_API_KEY=平台默认密钥
# 单价单位：元 / 1M tokens；输入价分缓存命中 / 未命中两档
export DEEPSEEK_PRICE_INPUT_CACHE_HIT_PER_1M=缓存命中输入单价_元每百万token
export DEEPSEEK_PRICE_INPUT_CACHE_MISS_PER_1M=缓存未命中输入单价_元每百万token
export DEEPSEEK_PRICE_OUTPUT_PER_1M=输出单价_元每百万token
# 兼容旧单位 元 / 1K tokens：DEEPSEEK_PRICE_INPUT_PER_1K 等仍可用，仅当 1M 单价未配置时生效
# 用户自带密钥：填写后进入 BYO 模式，跳过 0.1 元默认额度
# export DEEPSEEK_API_KEY=用户自己的密钥

# 3. 打包 & 启动
mvn clean package -DskipTests
java -jar target/intern-helper-0.10.0.jar

# 4. 打开页面
http://localhost:12231
```

Windows PowerShell：

```powershell
$env:MYSQL_PASSWORD="你的密码"
$env:CANDIDATE_SCHOOL="你的学校"
java -jar target\intern-helper-0.10.0.jar
```

示例数据导入：

```bash
curl -X POST http://localhost:12231/api/import/jobs \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary "@sample_jobs.json"
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/jobs?province=&city=&district=&status=&q=&cursor=&size=` | 岗位查询（游标模式，顺序翻页） |
| GET | `/api/jobs?page=2&sort=dailyMin&order=desc` | 岗位查询（offset 模式，跳页 / 排序） |
| GET | `/api/jobs/{id}` | 岗位详情 |
| POST | `/api/jobs` | 新增岗位（同公司同岗位名会拒绝） |
| PUT | `/api/jobs/{id}` | 修改岗位（只覆盖传入字段，薪资按结构化重算） |
| DELETE | `/api/jobs/{id}` | 删除岗位 |
| GET | `/api/jobs/{id}/greeting` | 生成话术（返回文案 + 方向 + 月数 + 天数 + 字数） |
| POST | `/api/jobs/{id}/contacted` | 记录一次投递，岗位置为已联系 |
| GET | `/api/jobs/pending/list` | 待投递岗位 |
| GET | `/api/jobs/regions/tree` | 省 → 市 筛选项 |
| GET | `/api/jobs/regions/districts?province=&city=` | 某市下真实存在的区县 |
| POST | `/api/import/jobs` | 批量导入，支持 `{"jobs":[...]}` 格式 |
| GET | `/api/import/batches` | 最近 20 次导入批次 |
| GET | `/api/import/latest` | 最后一次同步信息（页面顶部展示） |
| GET | `/api/stats` | 总览 + 各公司投递 Top |
| GET | `/api/stats/recent` | 最近 50 条投递记录 |
| GET | `/api/settings` | 读取系统设置（API Key 脱敏）与额度 |
| PUT | `/api/settings` | 保存系统设置，立即生效 |
| POST | `/api/settings/test-connection` | 测试模型连接 |
| GET | `/api/settings/usage` | 最近 20 条模型调用记录 |

列表接口返回结构（游标模式）：

```json
{
  "items": [ ... ],
  "total": 145,
  "size": 15,
  "mode": "cursor",
  "hasMore": true,
  "nextCursor": 209
}
```

offset 模式额外返回 `page` / `totalPages`，`mode` 为 `offset`。

排序参数：`sort` 可选 `months` / `days` / `dailyMin` / `dailyMax` / `id`，`order` 可选 `asc` / `desc`；**没解析出数值的岗位在排序时统一排最后**。（旧参数 `dailyPay` 已兼容映射到 `dailyMin`）

示例：

```bash
curl http://localhost:12231/api/jobs/1/greeting
```

```json
{
  "message": "您好，我是某大学2028届本科生。本人熟悉Java，有SpringBoot、MySQL、Redis相关经验，也熟悉AI agent相关工具。本人可连续实习至少6个月，每周5天。希望收到您的回复。",
  "direction": "中间件方向",
  "months": 6,
  "days": 5,
  "length": 83,
  "advice": "实习最好不超过6个月，每周最好不超过6天。",
  "engine": "RULE",
  "templateId": "concise",
  "warnings": [],
  "quotaExceeded": false
}
```

## 数据同步怎么用

**方式一：手动导入**

```bash
curl -X POST "http://localhost:12231/api/import/jobs?source=manual" \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary "@sample_jobs.json"
```

**方式二：丢文件自动导入**

把 `{"jobs":[...]}` 格式的 JSON 丢进 `import-drop/` 目录，系统每 5 分钟扫描一次，导入后自动把文件移到 `import-drop/imported/`。

爬虫脚本只要负责把文件写进这个目录，两边就解耦了：

```
python 抓取 → 写入 import-drop/jobs-2024xxxx.json
                    ↓ 定时扫描（5 分钟）
              job 表 + import_batch 批次记录
```

## 几个实现上的点

**1. 规则放数据库，不放代码**
话术的技能句匹配规则（测试 / 数据 / 算法 / AI / Go / 全栈 / 微服务 / 中间件 / 运维）存在 `greeting_rule` 表，按 `priority` 升序匹配，命中即用。想加一种岗位方向，插一条数据就行，不用改代码重新发版。

**2. 时长解析 + 边界截断**
岗位要求里常见「5天/周、6个月」。用正则解析出来，再做 clamp：月数限制在 2–12，天数限制在 2–7。超出部分由后端强制截断；同时页面和接口返回产品建议「实习最好不超过6个月，每周最好不超过6天。」，建议不写入对外话术。

**3. 深分页用游标，跳页才用 offset**
顺序翻页走 `where id < :cursor order by id desc limit 16`，深翻页耗时恒定；只有「跳转到第 N 页」「按字段排序」时才用 offset，因为这类需求本身就必须知道偏移量。数据量再大时可以把跳页限制住，或者按城市分片。

**4. 薪资格式统一折算成日薪区间**
岗位薪资格式很杂：`200-400元/天`、`4-6K`、`20-40K·15薪`、`面议`。统一折算成「最低日薪 / 最高日薪」两列（月薪按 21.75 个工作日），**不做平均**：平均值会把「200-400」和「300」压成同一个数，实际的区间差别很大。两列各自可排序，解析不出的为 null，排序时排最后。

**5. 实习岗 / 社招 自动区分**
放宽关键词抓岗位时会混进社招（月薪 20K+、P6/P7、3-5 年）。判定规则：标题含「实习」→ 实习；否则日薪 > 900（≈月薪 2 万）或标题命中「高级/资深/P6/P7」→ 社招。列表默认只查 category=INTERN，想看全部就切下拉。规则是启发式的，标错了可以在编辑页手动改。

**6. 地点拆成省 / 市 / 区**
原始数据是「广州海珠区」这种混在一起的字符串，既没法按省筛，也用不上索引。用内置城市字典拆成三段存三个字段，前端做成三级联动，查询走 `(province, city)` 精确匹配。历史数据在启动时自动回填。

**7. 导入去重 + 批次留痕**
按「公司 + 岗位名」判重；每次导入写一条 `import_batch`，页面顶部就能显示「最近同步：xx 时间 · 新增 N 条」。

**8. 分层与异常**
Controller 只做参数接收和组装，业务在 Service，数据访问在 Repository；全局 `@RestControllerAdvice` 统一返回 `{code, message}`，避免异常直接抛到页面。

## 版本历史

| 版本 | 日期 | 主要变化 |
|---|---|---|
| v0.10 | 2026-11-19 | 网页设置页（配置持久化）；AI 自由生成模式；修复保存设置的字段名映射；话术面板紧凑化与重新生成 |
| v0.9 | 2026-11-19 | 内置模板 + DeepSeek AI 润色；0.1 元本机额度与 BYO API；硬上限改为 12 个月 / 7 天并加入建议文案 |
| v0.8.1 | 2026-11-19 | 清掉自动投递残留（同步接口 / 脚本配置 / 前端投递弹窗）；默认端口改为 12231 |
| v0.8 | 2026-11-18 | 更改示例，完成初版可公开项目 |
| v0.7.1 | 2026-11-18 | 话术面板支持折叠：再点「生成话术」或点「收起」即收回，内容缓存不重复请求 |
| v0.7 | 2026-11-18 | 新增个人资料页面；根据个人资料产生两套话术 |
| v0.4.2 | 2026-11-16 | 岗位新增「实习 / 社招」自动判定与筛选 |
| v0.4.1 | 2026-11-16 | 单一日薪拆成最低 / 最高两列，各自可排序 |
| v0.4 | 2026-11-16 | 岗位编辑页、真正的删除、薪资按日 / 月两种口径录入 |
| v0.3 | 2026-11-16 | 跳转到指定页、三列可排序、手动新增岗位页、薪资格式统一折算 |
| v0.2 | 2026-11-16 | 省 / 市 / 区三级筛选、游标分页、导入批次留痕与自动入库 |
| v0.1 | 2026-11-16 | 首个版本：岗位库、话术生成、投递记录与统计 |

### v0.10（2026-11-19）

**主题：把配置搬进网页，并补上 AI 自由生成。**

- 新增 `/settings.html` 与 `/api/settings`：生成模式、默认模板、模型连接、测试连接、额度展示、单价设置；配置存数据库，保存后即时生效。
- 默认模板新增 **AI自由生成（`free`）**：不套模板，AI 直接依据个人资料与岗位信息创作；需配合 AI / HYBRID 模式。
- 修复设置页保存失败：前端短字段名（如 `engine`）与数据库全名（`greeting.engine`）统一映射，两种写法都接受。
- 话术面板两轮紧凑化，并在“收起”右侧新增“重新生成”按钮。
- 交付 jar 版本统一为 0.10.0。

### v0.9（2026-11-19）

**主题：把话术生成从硬编码拼接升级为「内置模板 + 可选 AI 润色」，并加入额度控制。**

- 新增简洁 / 正式两套内置模板（`src/main/resources/templates/`），确定性渲染替代原硬编码拼接。
- 个人资料中的学校简称、学历、技能标签、技能佐证、字数区间开始参与生成；资料页预览与真实生成逻辑对齐。
- 话术规则正则缓存改为按「规则 id + 原始正则」失效，数据库改规则后无需重启。
- 时长 / 出勤硬上限由 6 个月 / 每周 5 天改为 **12 个月 / 每周 7 天**；新增建议「实习最好不超过6个月，每周最好不超过6天。」，只展示不写入话术正文。
- 可选接入 DeepSeek：`GREETING_ENGINE=AI|HYBRID` 时在模板草稿上润色；模型失败、校验不通过、超时或额度不足时自动回退模板。
- 默认额度 0.1 元 / 本机实例，按保守估算预检；单价单位为 **元 / 1M tokens**，结算支持 DeepSeek 输入价「缓存命中 / 缓存未命中」双档与输出价，旧 `元 / 1K` 配置仍兼容；用户填写自己的 `DEEPSEEK_API_KEY` 后进入 BYO 模式，跳过默认额度。
- 新增 `ai_usage` 表记录模型调用用量；话术接口返回 `advice / engine / templateId / warnings / quotaExceeded`。
- 新增 `/settings.html` 设置页与 `/api/settings` 接口：生成模式、默认模板、模型连接、测试连接、额度展示、单价设置。配置存 `app_setting` 表，数据库值优先于环境变量并即时生效；API Key 脱敏展示，不写日志。
- 环境变量作为首次启动的种子，之后以数据库值为准；旧环境变量仍可用，但网页保存后会被数据库值覆盖。项目管理仍只适合本机，设置页不要暴露到公网。
- 默认模板新增 **AI自由生成（`free`）**：不使用内置模板草稿，直接把个人资料与岗位信息交给 AI 创作；需配合 `AI / HYBRID` 引擎，RULE 模式下会用简洁模板兜底并给出警告。同样受事实校验、额度控制与失败回退约束。

### v0.8.1（2026-11-19）

**主题：把「自动投递」相关的残留彻底清掉，端口换成 12231。**

对外版本一开始就把 CDP 自动投递整条链路删了，但代码里还留着一圈半截东西：一个只读外部发送日志的同步服务、一段指向 `打招呼脚本` 目录的配置、一整套前端投递弹窗。这次全部删除：

- 删除 `service/ContactSyncService`、`service/BossScriptLocator`
- 删除 `POST /api/jobs/sync-contacted` 接口，以及列表页的「同步已投状态」按钮
- 删除 `application.yml` 的 `app.deliver.*`（脚本目录 / `node-exe` / CDP 地址 / 发送间隔 / 单次上限）
- 删除列表页的投递确认弹窗、右下角进度面板、多选列与全选，以及所有 `/api/deliver*` 调用和配套 CSS，表格由 11 列回到 10 列
- 默认端口 `8080` → `12231`（`application.yml` 的 `server.port`）
- 版本号统一为 `0.8.1`，重新打包，jar 与源码一致

清理前那个同步按钮在干净环境里是坏的：它要读 `../../打招呼脚本/out/send_log.jsonl`，而该目录不在交付物内，点一次就 500。现在代码里既没有发送逻辑，也没有发送链路的配置和入口。

功能上少了一个「一键把历史已投岗位补成已联系」的按钮；这个能力原本只服务于脚本日志，随自动投递一起消失，正常投递仍用岗位行上的「标记已投」。

### v0.8（2026-11-18）

- 源码内的示例学校/专业改为占位值（`某大学` / `某专业`），不含任何真实个人信息
- 仓库版本号统一为 0.8.0


- 话术面板改为独占一行（`colspan` 全表宽），行高收紧、字号 15px、复制/收起横排

### v0.7.1（2026-11-18）

- 话术面板支持折叠：再点「生成话术」或点「收起」即收回，内容缓存不重复请求

### v0.7（2026-11-18）

- 新增「个人资料」页 `/profile.html`：身份 / 技能 / 时长出勤 / 风格，带实时预览
- 新增 `CandidateProfile` 实体、`/api/profile`（GET/PUT），保存时强制收敛（≤6 个月、≤5 天）
- `GreetingService` 改为读个人资料，支持 **简洁 / 正式** 两套句式；时长出勤取「岗位要求 ∩ 个人能力」


### v0.4.2（2026-11-16）

- 岗位新增 `category` 字段（INTERN / FULLTIME），按日薪与标题自动判定
- 列表页新增「岗位类型」筛选，默认只看实习岗
- 社招岗位在标题后显示红色「社招」标签
- 标题含「实习」的一律按实习处理（避免高薪实习被误判）
- 岗位接口新增 `category` 参数

### v0.4.1（2026-11-16）

- 单一日薪拆为「最低日薪 / 最高日薪」两列，取消平均值
- 两列均可独立升 / 降序排序（null 排最后）
- 日薪按原始区间直接换算（月薪 ÷ 21.75）
- 旧排序参数 `dailyPay` 兼容映射到 `dailyMin`

### v0.4（2026-11-16）

- 岗位编辑页 `/form.html?id=xxx`，可修改任意字段
- 薪资改为「日薪 / 月薪」二选一 + 最低 / 最高填写，自动折算日薪
- 列表页每行增加「编辑」「删除」按钮，删除有二次确认
- 后端新增 `PUT /api/jobs/{id}`，并实现真正的删除

### v0.3（2026-11-16）

- 底部「跳转到第 N 页」
- 实习时长 / 每周出勤 / 折算日薪三列，支持升序降序排序（null 排最后）
- 手动添加岗位页 `/add.html`（公司、岗位、地点、薪资、链接，选填时长与出勤）
- 薪资格式统一折算日薪（元 / 天、K / 月、元 / 月）

### v0.2（2026-11-16）

- 省 / 市 / 区三级筛选（内置城市字典，历史数据自动回填）
- 游标分页，深翻页耗时恒定
- 导入批次留痕 + 定时扫描投放目录自动入库
- 页面展示「最近同步时间 / 共 N 个岗位 / 第 N 页」

### v0.1（2026-11-16）

首个版本：岗位库管理、打招呼话术生成、投递记录与统计。技术栈 Spring Boot 3 + Spring Data JPA + MySQL 5.7。

## 还能怎么扩展（面试可以讲的下一步）

- 规则匹配已按「规则 id + 原始正则」缓存 `Pattern`，下一步可换 Aho-Corasick 做多模式匹配
- 投递记录加「HR 是否回复 / 是否约面」字段，把漏斗做成 触达 → 回复 → 简历 → 面试 四段
- 区县还没做「城市 → 区县」的字典，目前是查库取 distinct
- 加 Spring Security + 简单登录，做成多用户
- 前端换成 Vue/React，接口不变
