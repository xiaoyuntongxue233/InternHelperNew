# 实习投递助手（intern-helper）

一个把自己找实习的过程做成系统的后端项目：**岗位库 + 打招呼话术自动生成 + 投递记录 + 转化统计**。

> 背景：找实习时每天要投几十家，岗位信息散落在表格里、话术每次手写、投了多少/回了多少全靠回忆。这个项目把这些流程沉淀成一套可查询、可统计的系统。

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
3. **话术生成**：按岗位名/技术栈/要求原文匹配关键词，生成对应的技能句；自动解析岗位要求的「每周几天 / 实习几个月」并做上下限截断（在校生最多 6 个月、每周 5 天）
4. **投递记录**：每次投递落一条记录（公司、岗位、实际话术、时间、成功与否），并把岗位状态改成「已联系」
5. **统计**：今日/累计投递数、成功率、岗位库状态分布、各公司投递 Top
6. **数据同步**：JSON 批量导入（按「公司 + 岗位」去重）+ **导入批次留痕** + **定时扫描投放目录自动入库**
7. **岗位类型区分**：按日薪与标题自动识别「实习 / 社招」，列表默认只看实习岗，可切换查看社招
8. **页面**：列表页（筛选、排序、跳页、生成话术、编辑、删除）+ 新增/修改岗位表单页

## 页面

| 地址 | 作用 |
|---|---|
| `/` | 岗位列表：筛选、排序、跳页、生成话术、编辑、删除 |
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

四张表，由 JPA 自动建（`ddl-auto: update`）：

| 表 | 说明 |
|---|---|
| `job` | 岗位：公司、岗位名、**省/市/区**、原始地点、薪资、**实习月数/出勤天数/最低日薪/最高日薪**、链接、技术栈、要求原文、状态（NEW/CONTACTED） |
| `greeting_rule` | 话术规则：正则 → 技能句 → 方向名，可改数据库直接生效 |
| `application_record` | 投递记录：jobId、公司、岗位、话术、成功与否、时间 |
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

# 3. 打包 & 启动
mvn clean package -DskipTests
java -jar target/intern-helper-1.0.0.jar

# 4. 打开页面
http://localhost:8080
```

Windows PowerShell：

```powershell
$env:MYSQL_PASSWORD="你的密码"
$env:CANDIDATE_SCHOOL="你的学校"
java -jar target\intern-helper-1.0.0.jar
```

示例数据导入：

```bash
curl -X POST http://localhost:8080/api/import/jobs \
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
curl http://localhost:8080/api/jobs/1/greeting
```

```json
{
  "message": "您好，我是某大学2028届本科生，本人熟悉Java，有Springboot、MySQL、Redis相关经验，现可熟悉使用AI agent相关软件。本人可连续实习至少6个月，每周5天。希望收到您的回复。",
  "direction": "中间件方向",
  "months": 6,
  "days": 5,
  "length": 82
}
```

## 数据同步怎么用

**方式一：手动导入**

```bash
curl -X POST "http://localhost:8080/api/import/jobs?source=manual" \
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
岗位要求里常见「5天/周、6个月」。用正则解析出来，再做 clamp：月数限制在 2–6，天数限制在 2–5。因为岗位可能写「7天/周、12个月」，直接照抄会显得不现实，截断到在校生能做到的范围。

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
| v0.8 | 2026-11-18 | 更改示例，完成初版可公开项目 |
| v0.7.2 | 2026-11-18 | 移除批量投递相关板块；优化界面内容 |
| v0.7.1 | 2026-11-18 | 话术面板支持折叠：再点「生成话术」或点「收起」即收回，内容缓存不重复请求 |
| v0.7 | 2026-11-18 | 新增个人资料页面；根据个人资料产生两套话术 |
| v0.5.1 | 2026-11-17 | 修复打招呼脚本收尾空等；README 补齐参数与停止说明；新增本版本历史 |
| v0.5 | 2026-11-17 | 新增 `tools/boss-greeting/`：BOSS 直聘打招呼自动化脚本套件 |
| v0.4.2 | 2026-11-16 | 岗位新增「实习 / 社招」自动判定与筛选 |
| v0.4.1 | 2026-11-16 | 单一日薪拆成最低 / 最高两列，各自可排序 |
| v0.4 | 2026-11-16 | 岗位编辑页、真正的删除、薪资按日 / 月两种口径录入 |
| v0.3 | 2026-11-16 | 跳转到指定页、三列可排序、手动新增岗位页、薪资格式统一折算 |
| v0.2 | 2026-11-16 | 省 / 市 / 区三级筛选、游标分页、导入批次留痕与自动入库 |
| v0.1 | 2026-11-16 | 首个版本：岗位库、话术生成、投递记录与统计 |

### v0.8（2026-11-18）

- 源码内的示例学校/专业改为占位值（`某大学` / `某专业`），不含任何真实个人信息
- 仓库版本号统一为 0.8.0

### v0.7.2（2026-11-18）

- 移除批量投递：删除 `DeliverService` / `DeliverController`，前端去掉「开始投递」按钮
- 归档 7 个脚本到 `tools/boss-greeting/deprecated/`，附 README 说明反调试证据
- 话术面板改为独占一行（`colspan` 全表宽），行高收紧、字号 15px、复制/收起横排

### v0.7.1（2026-11-18）

- 话术面板支持折叠：再点「生成话术」或点「收起」即收回，内容缓存不重复请求

### v0.7（2026-11-18）

- 新增「个人资料」页 `/profile.html`：身份 / 技能 / 时长出勤 / 风格，带实时预览
- 新增 `CandidateProfile` 实体、`/api/profile`（GET/PUT），保存时强制收敛（≤6 个月、≤5 天）
- `GreetingService` 改为读个人资料，支持 **简洁 / 正式** 两套句式；时长出勤取「岗位要求 ∩ 个人能力」

### v0.5.1（2026-11-17）

打招呼脚本的收尾体验修复；`pom.xml` 版本号补到 `0.5.1`（v0.5 时漏了改）。

- `send_boss.mjs`：发满 `--limit` 或跑到队尾后直接退出，不再空等一个 40–90 秒的间隔（之前表现成「卡住不退出」）
- `tools/boss-greeting/README.md`：参数表补齐 `--dry-run` / `--check` / `--resume` / `--allow-placeholder` / `--daily-cap` / `--progress`；新增「如何停止 / 中途中断」一节

### v0.5（2026-11-17）

新增 `tools/boss-greeting/`：用 CDP 驱动已登录的 Edge，按岗位批量发送定制打招呼。

链路：抓岗位 → 生成话术 → 批量发送 → 服务端核对。

- **修掉 empty-chat 根因**：建会话后就地填写并发送，不再「建空会话 → 回消息列表 → 重进 → 因 0 条消息放弃」（41 条失败记录来自这个往返）
- **日志三态化**：`sent` / `failed` / `skipped`；`skipped`（空会话、已在会话中、岗位已下线）不再被当成失败永久拉黑
- **失败项移队尾 + 断点续跑**：`out/progress.json` 记录失败映射，`--resume` 从断点继续
- **会话归属校验收窄**：只比对会话标题元素 + 公司名规范化比较，避免把 A 公司话术发给 B 公司
- **发送校验三重条件**：输入框清空 + 我方消息条数增加 + 话术正文中段指纹（不再用对所有岗位都相同的前 12 字）
- **`clickSend` 轮询等待**：不再固定等 1.8 秒后补点按钮，避免同一个人收到两条
- **`cdp_common.mjs`**：无标签页时自动 `Target.createTarget`，解决「端口活着但没有页面」导致的崩溃
- **`sanitize_identity.mjs` + 占位符闸门**：脱敏工具；检测到占位话术会拒绝发送，避免把「XX大学」发给 HR
- **`test_core_logic.mjs`**：36 项回归测试，不碰浏览器

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

- 规则匹配目前每次编译正则，可以缓存 `Pattern` 或用 Aho-Corasick 做多模式匹配
- 投递记录加「HR 是否回复 / 是否约面」字段，把漏斗做成 触达 → 回复 → 简历 → 面试 四段
- 区县还没做「城市 → 区县」的字典，目前是查库取 distinct
- 加 Spring Security + 简单登录，做成多用户
- 前端换成 Vue/React，接口不变


---

## 废案版本

> 背景：BOSS 直聘前端上线反调试（`no-debug-entry`），CDP 驱动聊天页会让页面循环重载，
> 自动发送已不可行。批量发送代码转入 `tools/boss-greeting/deprecated/`，
> 项目重心回到「岗位库 + 话术生成」。

### v0.6.3（2026-11-18）

- `DeliverService` 仅在真有错误信号时才把「无结果」判为全失败，避免把正常跳过误判

### v0.6.1（2026-11-18）

- 新增「同步已投状态」：按 `send_log.jsonl` + `application_record` 补标已联系
- 列表默认按状态排序，已联系沉底；投递记录时间取日志真实时间

### v0.6.0（2026-11-18）

- 网页端新增「开始投递」：多选、确认弹窗、进度面板
- 后端新增 `DeliverService` / `DeliverController`（于 v0.7.2 移除）

