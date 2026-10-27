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
  - 持仓：成本价与盈亏统计，可为每只持仓设止损 / 止盈百分比提醒
  - 价格提醒：突破 / 跌破目标价提醒，可叠加放量条件
  - 提醒由全局巡查器统一巡检（关掉行情页也照样生效），刷新间隔随市场状态自动调整
    （交易 15 秒 / 午休 60 秒 / 其余 5 分钟，页面在后台时再降 4 倍）
- 模拟盘：服务端记账的 A 股模拟交易，手动买卖默认可用，自动选股 / 自动交易默认关闭；支持多账户（默认最多 5 个）
  - 手动盘：市价 / 限价委托、T+1、按板块校验申报数量（主板 / 创业板 100 股整数倍，科创板 200 股起、超出部分 1 股递增，北交所 100 股起可零股）、涨跌停与停牌校验、佣金 / 印花税 / 过户费、滑点
  - 自动交易：热榜候选 + 均线 / MACD / 量比 / 主力资金流 / 换手率 / 流通市值 / 分时均价线 / 涨停记忆打分选股；
    可选「严格过滤（硬条件）」一票否决：距年内低点上限、现价高于今开 / 分时均价、量比 / 5 日与 20 日均价与均量、
    近 N 日无涨停、总市值下限、集合竞价 / 早盘窗口 / 全天实际换手率（都除以自由流通股）、股东户数减少（集中度变小）、
    二季报 / 二季报预告 / 年报盈利、按账户资金剔除买不起的标的；
    止损 / 止盈 / 跌破 MA10 / 时间止损卖出；
    单票 20%、总仓 80%、日亏 3% 熔断、每日 20 笔上限，默认关闭，需在页面显式开启
  - 每个账户独立：初始资金、持仓、委托、成交、每日净值快照，以及各自的选股规则（候选池、因子权重、
    RSI / 涨幅 / 换手率 / 流通市值阈值、分时均价线占比、涨停记忆天数、首板/连板高度、近 N 日两年新高、缩量上涨、买入时间窗、次日开盘强制卖出，
    以及严格过滤的 18 项硬条件（年内低点比 / 集合竞价换手率 / 早盘换手率 / 实际换手率 / 开盘价 / 均价线 / 量比 / 均线均量趋势 / 涨停回避 / 总市值 /
    股东户数 / 盈利 / 资金可买性）等 59 项参数，存 `paper_strategy.params`）；最多 5 个账户，可对比不同规则，账户可重置或删除
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

多账户：默认每个用户最多 5 个模拟盘（`iblog.paper.max-accounts` / `IBLOG_PAPER_MAX_ACCOUNTS`），
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
`POST /api/stock/paper/selections/refresh` 是同步接口，严格过滤全开时一轮要 1~3 分钟：
前端 axios 单独放宽到 180s，`iblog-web/nginx.conf` 里该路径单独给了 300s 的 `proxy_read_timeout`
（其它行情接口仍是 20s），改动这两个值要成对改，否则会先被 nginx 504 截断而后端仍在跑。
新建账户的初始资金取 `iblog.paper.initial-cash`（可用 `IBLOG_PAPER_INITIAL_CASH` 覆盖）。

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
| GET | `/api/portal/articles` | 文章列表（`pageNum/pageSize/categoryId/tagId/keyword`）|
| GET | `/api/portal/articles/{id}` | 文章详情（累加浏览量）|
| GET/POST | `/api/portal/articles/{id}/comments` · `/api/portal/comments` | 评论查询 / 提交 |
| GET | `/api/portal/categories` · `/tags` · `/archive` · `/hot-articles` · `/site` | 官网公共数据 |

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
