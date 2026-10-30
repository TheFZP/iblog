# iblog

一个前后端分离的个人博客：**管理后台**（Java 17 + Vue3）与 **公开官网**（Vue3 SPA）共用同一套后端 API，可一键部署到阿里云。

---

## 一、技术栈

| 模块 | 技术 | 说明 |
| --- | --- | --- |
| `iblog-server` | Java 17 · Spring Boot 3.3 · **Gradle 8.10** · MyBatis-Plus 3.5 · MySQL 8 · Redis · JWT | 后端服务，后台 + 官网共用 |
| `iblog-admin` | Vue 3 · Vite · TypeScript · Element Plus · Pinia · Vue Router · **pnpm** | 管理后台 |
| `iblog-web` | Vue 3 · Vite · TypeScript · marked · **pnpm** | 官网前台（公开访问） |
| `deploy` | Docker Compose · MySQL 8 · Redis 7 · Nginx | 部署编排 |

### 官网为什么选 Vue3 + Vite SPA

按你的选择，官网使用与后台一致的 Vue3 技术栈，好处是**只有一套心智模型、构建产物纯静态、服务器零额外运行时**。

需要如实提醒一点：SPA 是客户端渲染，搜索引擎爬虫默认拿不到页面正文，**对 SEO 不友好**。个人博客如果希望文章被百度/Google 收录，有两条低成本的补救路径（都不需要重写业务代码）：

1. **预渲染**：给 `iblog-web` 加 `vite-plugin-prerender`，把首页、归档、关于等固定路由在构建时生成静态 HTML；
2. **升级 Nuxt 3**：路由、API 封装、组件几乎可直接复用，SSR/SSG 一步到位。

当前骨架已经把数据获取都收口在 `src/api/portal.ts`，无论走哪条路都只是构建层的改造。

---

## 二、目录结构

```
iblog/
├── iblog-server/                    # 后端（Spring Boot 3 + Gradle）
│   ├── src/main/java/com/iblog/
│   │   ├── common/                  # 统一响应 R、业务异常、全局异常处理
│   │   ├── config/                  # MyBatis-Plus、Redis、Web、JWT 配置
│   │   ├── security/                # JWT 工具、登录拦截器、用户上下文
│   │   └── module/
│   │       ├── entity/ mapper/ service/ dto/ vo/
│   │       ├── controller/admin/    # 后台接口  /api/admin/**
│   │       ├── controller/portal/   # 官网接口  /api/portal/**
│   │       └── runner/              # 首次启动初始化管理员
│   ├── src/main/resources/          # application.yml / dev / prod
│   │   └── db/                       # 01-schema.sql（建表）+ 02-data.sql（种子数据）
│   ├── build.gradle                 # 依赖与构建配置
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat        # Gradle Wrapper（无需本机装 Gradle）
│   └── Dockerfile
├── iblog-admin/                     # 管理后台（Vue3 + Element Plus）
│   ├── src/{api,layout,router,stores,views,styles}
│   ├── nginx.conf
│   └── Dockerfile
├── iblog-web/                       # 官网前台（Vue3 SPA）
│   ├── src/{api,components,composables,layout,router,views,utils,styles}
│   ├── nginx.conf
│   └── Dockerfile
├── deploy/
│   ├── docker-compose.yml           # mysql + redis + server + admin + web
│   ├── .env.example                 # 密码 / 密钥 / 端口
│   └── DEPLOY.md                    # 阿里云部署手册
└── README.md
```

> 建表 SQL 只有一份，放在 `iblog-server/src/main/resources/db/`：后端启动时读它自动建库建表，
> 部署时由 `docker-compose.yml` 把同一个目录挂进 MySQL 容器执行，不会出现两处不一致。

---

## 三、功能清单

**管理后台**

- 登录 / 退出（JWT + Redis 会话，可服务端主动注销；**同一账号可多设备同时在线**，每个设备一个独立令牌，
  退出登录只注销当前设备，其它设备保持在线）
- 仪表盘：文章数、发布/草稿数、分类标签数、评论数、待审核数、累计阅读
- 文章管理：新增、编辑、删除、发布/下架、置顶、Markdown 正文、分类与标签、封面、摘要、slug
- 分类 / 标签管理：增删改查，带文章数统计，删除分类时校验占用
- 评论管理：列表筛选、审核通过、驳回、删除（自动回写文章评论数）
- 美食管理：探店记录增删改查（店名、菜系、招牌菜、位置、经纬度 / 高德 POI、人均、推荐度）
- 访问日志：记录官网 `/api/portal/**` 每次调用（今日 PV / 独立 IP / 热门路径 Top10 + 明细分页，保留 30 天）
- 站点设置：站点信息、作者信息、评论开关、分页条数（带 Redis 缓存）

**官网前台（除行情外公开访问，无需登录）**

- 首页：编辑式刊头 + 头条 / 次要 / 更多三档版式，分页，分类 / 标签 / 热门文章侧边栏
- 文章详情：Markdown 渲染、浏览量统计、上下篇导航
- 评论：展示已通过评论、提交评论（默认待审核）
- 分类 / 标签 / 关键词检索列表
- 归档页（按年月分组）
- 关于页（作者信息 + 技术栈）
- 美食：探店记录列表，按菜系筛选 / 关键词搜索；移动端一键唤起高德导航（优先用 POI ID，坐标兜底）
- 行情：**仅对登录过管理后台的用户开放**（未登录时导航入口隐藏、页面显示需登录提示）
  - 后台登录时同时下发 HttpOnly Cookie，官网同源请求自动识别；官网与后台是同一主机的不同端口
    或同一主域的不同子域时无需再次登录
  - 两者是完全不同的网址时，从后台顶部「行情」入口进入，会携带一次性票据（120 秒、单次有效）
    自动换取官网登录态，后台会话不受影响
  - 自选股实时报价、大盘指数、同花顺人气热榜、K 线 / 分时 / 五档盘口、个股搜索
  - 资金流：当日主力 / 超大单 / 大单 / 中单 / 小单分档，以及近 30 日主力净流入趋势
  - 技术分析：个股详情页「技术分析」进入，四个子页签
    - 技术指标：MA5/10/20/30 排列、MACD（金叉死叉 + 柱状）、KDJ（超买超卖）、WR、RSI
    - 筹码分布：按日 K 成交量在当日高低区间做三角分布摊到价格档、逐根累积，可选按换手率衰减历史筹码；
      给出平均成本、峰值价位、COST5/15/50/85/95 成本分位与获利盘比例
    - 支撑阻力：波段高低点聚类（相近 0.5% 归一档）取现价 ±25% 内各前 3 条，不足时用斐波那契回撤位补足，
      并给出距最近支撑 / 阻力的百分比与盈亏比
    - 信号回测：8 条日线规则（均线金叉 / 多头排列 / MACD 金叉 / KDJ 低位金叉 / RSI 上穿 30 / 破 60 日阻力 /
      放量上涨 / 逼近涨停）逐条回测 1/3/5/10 个交易日的胜率、期望收益、最大回撤，非重叠交易
  - 持仓组合：行情页「持仓组合」进入，按市值权重给出总盈亏、当日盈亏、集中度（HHI + 前三大权重）、
    涨跌分布、行业分布（含标签覆盖率）、加权 PE；缺昨收的持仓单列不计入当日盈亏
  - AI 分析：个股详情页「AI 分析」抽屉，流式对话（DeepSeek），模型可自行调用 17 个工具
    （实时行情、批量行情、K 线+技术指标、资金流、行业、指数、分时、热榜、搜索、用户档案、汇率、
    联网搜索/抓取、问财选股、选股卡片）后再作答；回答末尾按提示词要求给出「📋 操作建议」
  - 问财选股：自然语言选股（「连续三天放量上涨且市值小于 100 亿」），由问财接口返回结构化表格，
    对话里也会以选股卡片形式给出（最多 20 只，可点击跳转）
  - AI 的 API Key 只存服务端 `stock_setting`（后台「AI 配置」菜单填写），不下发到浏览器；
    工具全部在后端 Java 执行，前端只负责渲染事件流
  - 问财选股页：导航「问财选股」进入，独立页面输入自然语言条件，表格展示问财结构化结果，
    支持本地翻页（服务端忽略 page，故整批拉回后本地切页）、保留上次结果、一键「AI 分析这批股票」；
    「✨ AI 优化」按钮先把口语条件交给 AI 改写成问财更易识别的句式（不自动提交，改完仍可编辑）
  - 全局 AI 助手：`Ctrl + N` 或导航栏 AI 图标唤起，不绑定个股，自动带自选 / 持仓 / 热榜上下文；
    与详情页 AI 抽屉共用同一个面板与后端 Agent
  - 投资画像：详情页「投资画像」按钮打开，Markdown 编辑 + 预览，存服务端 `stock_setting`，
    AI 每次对话都会注入该画像（空则按中性偏稳健处理）
  - K 线均线设置：图表面板可选 5/10/20/30/60/120/250 日任意组合（最多 6 条），
    并可叠加「近 30 日最高 / 最低」参考线；设置存浏览器本地
  - 数据新鲜度：行情头部常显「数据 HH:mm」（最近一次成功刷新的时刻，不是当前时间），
    刷新失败时保留旧数据并给出「重试」按钮
  - 分时 T+0 信号：分时图标注放量急拉 / 放量急跌 / 无量拉升 / 突破 / 破位 / 疑似诱多 / 疑似诱空，
    同类信号 10 分钟内只标首个，上午 09:30 集合竞价分钟不参与；顶部给出一句话汇总
  - 历史信号胜率：技术分析第五个页签「历史胜率」，可切换 1 / 3 / 5 / 10 个交易日的持有期，
    统计样本量、胜负平、胜率、平均盈利、平均亏损、盈亏比（profit factor）与最佳 / 最差单笔，
    并给出该信号在各持有期下的胜率对照；**口径为「信号日之后第 N 个交易日收盘」，
    按用户实际查看过的记录统计，不代表策略胜率**，样本少于 5 条会标「样本少」，
    底部固定展示免责说明与盈亏比口径（无亏损或全亏时显示 --，该比值无意义）
  - 自选异动通知：对自选股盯涨停 / 跌停 / 涨跌超 ±5% / ±7% / 快速拉升 / 快速下跌
    （默认 30 秒内相对昨收变动 2%，可调 1~5），每只股票每类每天只通知一次；
    港股无涨跌停仍报 ±5% / ±7%，最近 7 天的记录可在设置里查看
  - 持仓：成本价与盈亏统计，可为每只持仓设止损 / 止盈百分比提醒
  - 价格提醒：突破 / 跌破目标价提醒，可叠加放量条件
  - 均线提醒：详情页「均线提醒」按钮，为任意股票配置 5/10/20/30/60 日的上穿 / 下穿 / 双向穿越提醒，
    每只股票每个周期每天只通知一次，触发记录可在同一弹窗的「触发历史」查看
  - 提醒由全局巡查器统一巡检（关掉行情页也照样生效），刷新间隔随市场状态自动调整
    （默认交易 15 秒 / 午休 60 秒 / 其余 5 分钟，页面在后台时再降 4 倍），
    可在设置里改成 10 秒 ~ 5 分钟或「不自动刷新」
  - 设置中心：`Ctrl + ,` 或导航栏齿轮图标打开，四个页签
    - 通知：通知权限状态与申请、总开关、八类自选异动逐项开关、快速变动阈值、
      价格提醒开关、最近 7 天触发记录
    - 刷新：大盘指数 / 个股行情 / 日 K / 分时四个独立间隔，选项 10 秒 ~ 5 分钟或关闭自动刷新
    - 图表：K 线均线周期组合与「近 30 日最高 / 最低」参考线
    - 关于：AI 服务状态、快捷键、提醒能力边界说明
  - 全局搜索：`Ctrl + K` 打开，同时搜文章与股票代码 / 名称，`↑` `↓` 选择、`Enter` 跳转，
    无选中项时按关键词跳文章列表
  - 港股交易日：内置 2024–2027 港股法定假期表（68 条），港股时段判断会排除假期；
    超出收录年份自动降级为「只判周末」而不是判错
- 模拟盘：服务端记账的 A 股模拟交易，手动买卖默认可用，自动选股 / 自动交易默认关闭；支持多账户（数量不限）
  - 手动盘：市价 / 限价委托、T+1、按板块校验申报数量（主板 / 创业板 100 股整数倍，科创板 200 股起、超出部分 1 股递增，北交所 100 股起可零股）、涨跌停与停牌校验、佣金 / 印花税 / 过户费、滑点
  - 自动交易：热榜候选 + 均线 / MACD / 量比 / 主力资金流 / 换手率 / 流通市值 / 分时均价线 / 涨停记忆打分选股；
    可选「严格过滤（硬条件）」一票否决：距年内低点上限、现价高于今开 / 分时均价、量比 / 5 日与 20 日均价与均量、
    近 N 日无涨停、总市值下限、集合竞价 / 早盘窗口 / 全天实际换手率（都除以自由流通股）、股东户数减少（集中度变小）、
    二季报 / 二季报预告 / 年报盈利、按账户资金剔除买不起的标的；
    止损 / 止盈 / 跌破 MA10 / 时间止损卖出；
    市场情绪门控（默认关）：情绪分低于开仓线不开新仓、低于清仓线无条件清仓、只要热榜前 N 名 / 热度须上升、
    热榜名次加分、涨幅上限随情绪自适应（冰点收紧、亢奋放宽）；
    单票 20%、总仓 80%、日亏 3% 熔断、每日 20 笔上限，默认关闭，需在页面显式开启
  - 每个账户独立：初始资金、持仓、委托、成交、每日净值快照，以及各自的选股规则（候选池、因子权重、
    RSI / 涨幅 / 换手率 / 流通市值阈值、分时均价线占比、涨停记忆天数、首板/连板高度、近 N 日两年新高、缩量上涨、买入时间窗、次日开盘强制卖出，
    以及严格过滤的 18 项硬条件（年内低点比 / 集合竞价换手率 / 早盘换手率 / 实际换手率 / 开盘价 / 均价线 / 量比 / 均线均量趋势 / 涨停回避 / 总市值 /
    股东户数 / 盈利 / 资金可买性）等 59 项参数，存 `paper_strategy.params`）；账户数量不限，可对比不同规则，账户可重置或删除
  - 规则模板：59 项参数可另存为多套命名模板（`paper_rule` 表），在任意账户上一键应用 / 切换，应用后仍可单独微调
  - 入口：官网导航 / 管理后台顶部「模拟盘」；后台入口经一次性票据直达，免二次登录

---

## 四、本地启动

### 1. 准备 MySQL 与 Redis

**MySQL 8 不需要手工建库。** 后端启动时会自己检查：库不存在就建库、表不存在就建表并写入种子数据
（由 `iblog.db.auto-init` 控制，开发环境默认 **开启**，生产环境默认关闭）。所以只需要保证
**MySQL 服务已启动，并且 `application-dev.yml` 里的账号密码是对的**即可。

启动后如果还是连不上，控制台会给出一段中文提示块（而不是几百行堆栈），照着改就行：

```
==========================================================
  数据库账号或密码不正确（Access denied）。
  请核对 iblog-server/src/main/resources/application-dev.yml 里的 datasource 配置。
  环境准备详见 README「四、本地启动」
==========================================================
```

想手工执行建表脚本（或在生产环境初始化）时：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS iblog DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -uroot -p iblog < iblog-server/src/main/resources/db/01-schema.sql
mysql -uroot -p iblog < iblog-server/src/main/resources/db/02-data.sql
```

两个脚本都是幂等的（`CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`），**重复执行不会清空或重复写入已有数据**。

Redis 本地启动即可：

```bash
redis-server
```

也可以用 Docker 只跑这两个依赖：

```bash
docker run -d --name iblog-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=iblog -e TZ=Asia/Shanghai \
  -v "$PWD/iblog-server/src/main/resources/db:/docker-entrypoint-initdb.d:ro" mysql:8.0

docker run -d --name iblog-redis -p 6379:6379 redis:7-alpine
```

### 2. 启动后端

```bash
cd iblog-server
./gradlew bootRun          # Windows 用 gradlew.bat bootRun
```

常用构建命令：

```bash
./gradlew bootRun          # 本地启动（dev 环境）
./gradlew clean build      # 编译 + 测试 + 打 jar
./gradlew bootJar -x test  # 跳过测试，只打可执行 jar → build/libs/iblog-server.jar
./gradlew dependencies     # 查看依赖树
```

> 项目自带 Gradle Wrapper，**本机不需要预装 Gradle**（首次运行会自动下载 8.10.2）。
> 如果本地已有 Gradle，也可以直接用 `gradle bootRun`。

**依赖来源（全部走公网镜像，不使用公司内网）**

| 内容 | 来源 |
| --- | --- |
| Gradle 发行包 | `mirrors.aliyun.com/macports/distfiles/gradle` |
| Maven 依赖 | `maven.aliyun.com/repository/public`（公网兜底 mavenCentral） |
| Gradle 插件 | `maven.aliyun.com/repository/gradle-plugin` |
| pnpm | `registry.npmmirror.com`（写在 `iblog-admin/.npmrc` 与 `iblog-web/.npmrc`） |

`build.gradle` 里显式调用了 `repositories.clear()`，会清掉机器级 `init.gradle` 往所有项目注入的内网 Nexus，因此**离开公司内网也能正常构建**。

- 默认 `dev` 环境，连接 `localhost:3306/iblog`，Redis `localhost:6379`（账号密码以 `application-dev.yml` 为准）
- 数据库或表不存在时**自动创建**（`iblog.db.auto-init: true`）；生产环境为 `false`，只做自检不改结构
- 首次启动会自动创建管理员账号：**admin / admin123**
- 接口文档：<http://localhost:10730/swagger-ui.html>

### 在 IntelliJ IDEA 中打开

仓库是「聚合 Gradle 工程」：根目录 `settings.gradle` 通过 `include 'iblog-server'` 把后端纳入同一个构建。**直接用 IDEA 打开仓库根目录即可，不需要手动新建模块。**

1. `File → Open` 选择仓库根目录 `iblog`
2. IDEA 检测到 `settings.gradle` 后提示导入，选择 **Load Gradle Project / Reload Gradle Project**
3. 等待同步完成，`iblog-server/src/main/java` 会被标记为 Sources Root，`IblogApplication.java` 左侧出现绿色运行三角

没有弹提示时，用右侧 Gradle 工具面板点 **Reload All Gradle Projects**（🔄），
或右键根目录的 `build.gradle` → **Link Gradle Project**。

Gradle JVM 在 `Settings → Build Tools → Gradle → Gradle JVM` 里选 **JDK 17 或更高版本**。

> 两种构建入口都可用：仓库根目录 `./gradlew :iblog-server:bootRun`，或进入 `iblog-server` 执行 `./gradlew bootRun`。
> `iblog-admin` / `iblog-web` 是前端工程（均用 pnpm），在 IDEA 里不会成为 Gradle 模块，属正常现象。

### 3. 启动管理后台

`iblog-admin` 使用 **pnpm** 管理依赖：

```bash
cd iblog-admin
pnpm install
pnpm dev           # http://localhost:5180
```

> 没装 pnpm 的话，用 Node 自带的 corepack 一行启用：`corepack enable pnpm`
> （或 `npm i -g pnpm`）。`package.json` 里的 `packageManager` 字段已锁定版本，corepack 会自动使用该版本。

### 4. 启动官网前台

```bash
cd iblog-web
pnpm install
pnpm dev           # http://localhost:5181
```

> 没装 pnpm 的话，用 Node 自带的 corepack 一行启用：`corepack enable pnpm`（或 `npm i -g pnpm`）。
> `package.json` 里的 `packageManager` 字段已锁定版本，corepack 会自动使用该版本。

两个前端的 `/api` 请求都由 Vite 代理到 `http://localhost:10730`（见各自 `vite.config.ts`）。

> **端口说明**：后端 `10730`、管理后台 `5180`、官网 `5181`。都不是框架默认端口（8080/5173），
> 因为本机同时开着多个项目时默认端口极易被抢。两个前端都设了 `strictPort: true` ——
> 端口被占会直接报错退出，而不是悄悄换一个端口，免得浏览器里打开的其实是别的项目。
> 后端端口可用环境变量 `IBLOG_SERVER_PORT` 覆盖，例如 `IBLOG_SERVER_PORT=10731 ./gradlew bootRun`。

### 5. 跑测试

```bash
cd iblog-server && ./gradlew test    # 后端单测（资金流解析、行情字段解析等）
cd iblog-web && pnpm test            # 官网前端纯逻辑单测（提醒判定规则）
```

> 前端的测试用 Node 原生 `node:test` + `--experimental-strip-types` 直接跑 TS 源码，
> 不引入 vitest / jsdom，被测模块（如 `src/utils/positionAlertRules.ts`）刻意做成零依赖。
> 需要 Node ≥ 22.6。

### 6. 模拟盘（自动交易默认关闭）

模拟盘是服务端记账的模拟交易：登录后手动买卖直接可用；`iblog.paper.enabled` 是**自动交易总开关**，
默认 `false`，此时自动选股 / 自动下单不会执行，手动交易不受影响。

本地打开自动交易（`application-dev.yml` 已把 `force-trading-hours` 设为 true，晚上也能下单调试）：

```yaml
iblog:
  paper:
    enabled: true          # 打开自动交易（手动买卖本来就能用）
    initial-cash: 1000000  # 每个新账户的初始资金
```

生产环境用环境变量 `IBLOG_PAPER_ENABLED=true` 打开自动交易。自动交易还有第二道开关：用户在 `/paper`
页面点「自动交易」才会开启（默认关），服务端总开关没开时不允许打开。费率、滑点、仓位上限、
熔断阈值等参数都在 `iblog.paper.*`（完整字段见 `IblogProperties.Paper`）。

多账户：账户数量不设上限（想开多少开多少，不再有 `max-accounts` 配置），
每个账户有独立的初始资金、持仓和选股规则；在 `/paper` 顶部账户栏切换、新建、改名、删除。
选股规则在「高级规则（选股打分）」里按账户保存，改完立即影响该账户下一轮自动选股。
「严格过滤（硬条件）」分组是另一套语义：质变的因子权重只管打分，这里的每一条都是**一票否决**，
只要有一条不满足就直接从候选池里剔掉（默认全部关闭，全部为 0 / false 时行为与之前一致）。
其中「资金可买性」按账户当前总资产 × 单票仓位上限与可用现金取小算预算，够不上该板块最小申报数量就剔除，
所以 3 万本金的账户不会选出 200 元以上的科创板票。基本面（自由流通股、股东户数、财报 / 业绩预告）
走东方财富数据中心，失败时按「不满足」处理并计入本轮请求预算，不会让整轮选股失败。
换手率类条件里的「自由流通股」取东财 F10 的 `FREELIQCI_SHARES`（不是流通股 `FREE_SHARES`），
「集合竞价换手率」只看 9:25 撮合那一刻的量（即分钟分时的 09:30 那根，09:31 起才是连续竞价），
「早盘换手率」看 9:25–9:40 的**累计**量，两者是独立参数。
量比按标准口径算：**当日每分钟均量 ÷ 前 5 日每分钟均量**，盘中用已走过的交易分钟数（上午 120 + 下午 120）
归一化，所以 10 点看到 1.5 就是真的放量 1.5 倍，不会因为只走了半天而被压低好几倍；收盘后与旧口径等价。
盘中判断「最后一根日 K 是不是今天」靠交易日历，盘前或行情未刷新时按整日计算。
`stock_kline` 里当天那根 K 在盘中会随成交量变化，所以**交易时段内一律视为需要刷新**
（每只票每分钟最多回源一次，由 `KlineCacheService.REFETCH_INTERVAL_MS` 节流；收盘后不再回源）。
只按「日期是不是今天」判断会让当天那根停在开盘一分钟的累计量，量比 / 当日涨幅 / 均线全部失真。
`POST /api/stock/paper/selections/refresh` 是同步接口，严格过滤全开时一轮要 1~3 分钟：
前端 axios 单独放宽到 180s，`iblog-web/nginx.conf` 里该路径单独给了 300s 的 `proxy_read_timeout`
（其它行情接口仍是 20s），改动这两个值要成对改，否则会先被 nginx 504 截断而后端仍在跑。
新建账户的初始资金取 `iblog.paper.initial-cash`（可用 `IBLOG_PAPER_INITIAL_CASH` 覆盖）。

**市场情绪（热度）维度**：`/paper` 高级规则里多了一个「市场情绪（热度）」分组，默认关闭，
打开后才参与买卖决策。情绪分是 0~100 的赚钱效应温度计，由五项分项加权得到
（涨跌家数比 30、涨跌停家数 25、炸板率 15、指数涨幅 20、成交额 10），映射成
「亢奋 / 活跃 / 中性 / 低迷 / 冰点」五档。分项数据取不到的**不按 0 分算**，而是把它的权重
摊给其余分项——否则一次上游超时就会把情绪判成冰点，进而清仓。
可用参数（全部 0 / false 时行为与以前完全一致）：
- `sentimentEnabled` 总开关；
- `sentimentBuyMinScore` 情绪低于该分不开新仓（引擎会记一条「暂停买入」信号，同一天同一原因只记一次）；
- `sentimentExitScore` 情绪低于该分无条件清仓（保存时若高于开仓线会被压平到开仓线，避免「刚买就清」）；
- `hotRankTopN` 候选必须来自热榜前 N 名；
- `requireHotRankUp` 候选的热榜排名必须比上次上升（热度退潮的票直接剔除）；
- `hotnessScore` 按热榜名次加分（第 1 名拿满分，用「名次 / 候选池大小」归一，这样池子改大改小都是同一把尺子）；
- `sentimentChangeMaxPct` 当日涨幅上限随情绪自适应：情绪 40 分时用原上限，越高越接近该值，
  越低按 60% 收紧（冰点日追高就是接盘）。
情绪数据走东财涨跌家数 / 涨跌停池 / 炸板池 + 指数与成交额，服务端 60 秒缓存，
失败时沿用上一份快照并标 `stale=true`；从未成功过时 `available=false`，
此时引擎**不会**据此拦单或清仓（没数据不能当「情绪为 0」用两次，那样等于把策略关掉）。
查询接口：`GET /api/stock/sentiment`。

**双链路**：东财 `push2delay`（涨跌家数 + 成交额）会对部分机房 IP 直接拒连——线上服务器实测
该域名全部 `HTTP 000`，而同一台机器访问 `push2ex`（涨跌停池）、腾讯、新浪都正常，属于按 IP 段的
主动限制。所以主链路拿不到数据时会自动切到兜底链路：

- 涨跌家数：新浪按涨跌幅降序分页（每页 100 条，`getHQNodeStockCount` 取总数 → 对「该页末条涨跌幅 ≤ 0」
  二分定位 0% 边界页，约 6~7 次请求得出涨 / 跌 / 平家数，避免逐页扫 56 页）；
- 指数涨幅与成交额：腾讯 `qt.gtimg.cn`（`[32]` 涨跌幅、`[37]` 成交额万元，只累加沪市 + 深市）；
- 涨跌停 / 炸板池：仍走 `push2ex`（该域名不受影响）。

兜底链路有 8 秒总预算、单请求 2 秒超时——它挂在 15 秒一次的引擎 tick 上，宁可沿用旧快照
也不能把 tick 拖过下一轮。熔断器只由东财主链路驱动，兜底成功**不**清零熔断计数：
否则每个冷却期一过又会白等一次东财超时。

### 7. AI 分析（需在后台填 API Key）

官网个股详情页的「AI 分析」抽屉调 DeepSeek 流式接口，Key 只需要在后台填一次：

1. 启动后台 → 左侧菜单「AI 配置」→ 填 `DeepSeek API Key`（`sk-...`）→ 保存；
2. 打开官网个股页 → 「AI 分析」→ 随便问一句验证能出字。

Key 存 MySQL 的 `stock_setting` 表（`ai_api_key`，secret），接口只回传打码值，
浏览器永远拿不到明文；改 Key / 换模型都不用重启后端。

需要额外说明的两点：

- **`iblog-web/public/chameleon.js` 不能删**。问财接口在非问财域名、无 Cookie `v` 时
  一律 nginx 403，而 `v` 只能由这个脚本在真实浏览器里算出来（服务端沙箱跑不出来）。
  流程是「浏览器算 v → 随对话请求带给后端 → 后端带反爬头转发」，脚本是前半段。
- **AI 对话是 SSE 长连接**，`iblog-web/nginx.conf` 里 `location = /api/stock/ai/chat`
  单独关了 `proxy_buffering` 并给 300s 超时，不要合并回 `/api/stock/`。

---

## 五、接口约定

统一响应体：

```json
{ "code": 200, "msg": "操作成功", "data": {} }
```

`code`：`200` 成功，`400` 参数/业务错误，`401` 未登录或登录失效，`403` 无权限，`500` 系统异常。

鉴权：除 `/api/admin/auth/login` 外，所有 `/api/admin/**` 需带请求头

```
Authorization: Bearer <token>
```

主要接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/auth/login` | 登录 |
| POST | `/api/admin/auth/logout` | 退出 |
| GET | `/api/admin/dashboard` | 仪表盘统计 |
| GET/POST/PUT/DELETE | `/api/admin/articles` | 文章增删改查 |
| PUT | `/api/admin/articles/{id}/status?status=1` | 发布 / 下架 |
| GET/POST/PUT/DELETE | `/api/admin/categories` | 分类管理 |
| GET/POST/PUT/DELETE | `/api/admin/tags` | 标签管理 |
| GET | `/api/admin/comments` | 评论列表 |
| PUT | `/api/admin/comments/{id}/approve` | 审核通过 |
| GET/PUT | `/api/admin/site/config` | 站点配置 |
| GET | `/api/admin/visit-logs` · `/api/admin/visit-logs/summary` | 官网访问日志明细分页 / 今日统计（PV、独立 IP、热门路径） |
| GET | `/api/portal/articles` | 文章列表（`pageNum/pageSize/categoryId/tagId/keyword`）|
| GET | `/api/portal/articles/{id}` | 文章详情（累加浏览量）|
| GET/POST | `/api/portal/articles/{id}/comments` · `/api/portal/comments` | 评论查询 / 提交 |
| GET | `/api/portal/categories` · `/tags` · `/archive` · `/hot-articles` · `/site` | 官网公共数据 |

行情接口（`/api/stock/**`，登录后可用）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/stock/quote` · `/quotes` | 单只 / 批量实时报价 |
| GET | `/api/stock/kline?code=&period=&limit=` | K 线；`period` 为 `day/week/month/m5/m15/m30/m60`，`limit` 默认 120（上限 640，分钟 K 固定 320 根）|
| GET | `/api/stock/intraday` · `/orderbook` | 分时 / 五档盘口 |
| GET | `/api/stock/indices` · `/hotlist` · `/search` | 大盘指数 / 人气热榜 / 个股搜索 |
| GET | `/api/stock/sentiment` | 市场情绪温度计（0~100 分 + 涨跌家数 / 涨跌停 / 炸板率 / 成交额） |
| GET | `/api/stock/moneyflow` · `/moneyflow/history` | 资金流分档 / 近 N 日主力净流入趋势 |
| GET | `/api/stock/industry` | 行业对比（相对沪深 300 表现、营收排名、行业均值）|

AI 接口（`/api/stock/ai/**`，登录后可用；官网用 Cookie、后台用 header 令牌都能调）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/stock/ai/config` | 面板初始化（是否已配 Key、模型名、开关；不含 Key）|
| POST | `/api/stock/ai/chat` | 流式对话（SSE），`event: reasoning/content/tool_call/tool_result/done/error` |
| POST | `/api/stock/ai/iwencai` | 问财选股代理（需前端带 `v`；限流回 429，前端换 v 重试一次）|
| POST | `/api/stock/ai/optimize-query` | 把口语化选股条件用 AI 改写为问财认的语句（请求 `{query}`，响应 `{query}`；上限 500 字，非流式）|
| GET / PUT | `/api/stock/ai/profile` | 读 / 写用户投资画像（Markdown 文本，上限 20000 字；GET 返回 `content` + `updatedAt`）|

模拟盘接口（`/api/stock/paper/**`，登录后可用；官网用 Cookie、后台用 header 令牌都能调）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST | `/api/stock/paper/accounts` | 账户列表 / 新建账户 |
| PATCH / DELETE | `/api/stock/paper/accounts/{id}` | 账户改名 / 删除（至少保留一个）|
| GET | `/api/stock/paper/account` | 账户总览（资产 / 今日盈亏 / 持仓数 / 自动交易开关）|
| GET | `/api/stock/paper/positions` | 持仓（含可用数量、浮动盈亏）|
| GET / POST | `/api/stock/paper/orders` · `DELETE /orders/{id}` | 委托列表 / 下单 / 撤单 |
| GET | `/api/stock/paper/trades` · `/nav` | 成交流水 / 每日净值 |
| GET / PUT | `/api/stock/paper/strategies` · `/strategies/{id}` | 策略开关与参数（59 项选股规则，按账户保存）|
| GET / POST | `/api/stock/paper/rules` · `PUT/DELETE /rules/{id}` | 规则模板列表 / 新建 / 改 / 删 |
| POST | `/api/stock/paper/accounts/{id}/apply-rule/{ruleId}` | 把模板参数一键应用到指定账户 |
| GET / POST | `/api/stock/paper/selections` · `/selections/refresh` | 今日选股结果 / 立即重新选股 |
| GET | `/api/stock/paper/engine/status` | 引擎与自动交易状态 |
| POST | `/api/stock/paper/account/auto-trade` · `/account/reset` | 开关自动交易 / 重置账户 |

> 除 `/accounts` 外，所有模拟盘接口都接受可选 `?accountId=`；不传时使用当前用户的主账户（最早创建的那个）。

---

## 六、部署到阿里云

完整步骤见 [`deploy/DEPLOY.md`](deploy/DEPLOY.md)，速览：

```bash
cd deploy
cp .env.example .env      # 改数据库密码、JWT 密钥
docker compose up -d --build
```

> **2 核 2G 等低配服务器**：先在本地执行 `bash deploy/build-local.sh` 打好产物（jar + 前端静态文件），
> 把 `deploy/` 整个上传到服务器，再把 `.env` 的构建模式切到 B —— 服务器只做 COPY、不编译，
> 构建几秒完成且几乎不吃内存。详见 `deploy/DEPLOY.md`「二、4. 小内存服务器」。

| 地址 | 说明 |
| --- | --- |
| `http://<IP>/` | 官网 |
| `http://<IP>:81/` | 管理后台 |
| `http://<IP>:8080/api` | 后端接口 |
| `http://<IP>:8080/swagger-ui.html` | 接口文档 |

---

## 七、后续可以加什么

- 文章 Markdown 富文本编辑器（如 `md-editor-v3`）与图片上传到 OSS
- 评论邮件/微信通知、防刷限流
- 全文检索（MySQL 全文索引 或 Elasticsearch）
- 文章草稿自动保存、版本历史
- 官网预渲染 / Nuxt 3 改造以提升 SEO
