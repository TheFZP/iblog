-- =============================================================
--  iblog 表结构（MySQL 8.0+，字符集 utf8mb4）
--  说明：
--    1. 全部使用 CREATE TABLE IF NOT EXISTS，可重复执行，不会破坏已有数据；
--    2. 不写 CREATE DATABASE / USE，建库由后端启动自举或部署脚本负责；
--    3. 管理员账号（admin / admin123）由后端启动时用 BCrypt 运行时生成，
--       因此这里不写死密码。
--  手工执行：mysql -uroot -p iblog < 01-schema.sql
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 后台用户
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '登录名',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 停用',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='后台用户';

-- -------------------------------------------------------------
-- 分类
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `slug`        VARCHAR(50) NOT NULL COMMENT '英文别名',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '描述',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章分类';

-- -------------------------------------------------------------
-- 标签
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_tag`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50) NOT NULL COMMENT '标签名称',
    `slug`        VARCHAR(50) NOT NULL COMMENT '英文别名',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序值',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章标签';

-- -------------------------------------------------------------
-- 文章
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_article`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`         VARCHAR(200) NOT NULL COMMENT '标题',
    `slug`          VARCHAR(120)  DEFAULT NULL COMMENT '英文别名，可用于 URL',
    `summary`       VARCHAR(500)  DEFAULT NULL COMMENT '摘要',
    `content`       LONGTEXT COMMENT '正文（Markdown）',
    `cover`         VARCHAR(255)  DEFAULT NULL COMMENT '封面图',
    `category_id`   BIGINT        DEFAULT NULL COMMENT '分类 ID',
    `author`        VARCHAR(50)   DEFAULT NULL COMMENT '作者',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 草稿 1 已发布',
    `is_top`        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 置顶',
    `view_count`    INT          NOT NULL DEFAULT 0 COMMENT '浏览量',
    `comment_count` INT          NOT NULL DEFAULT 0 COMMENT '评论数（已通过）',
    `publish_time`  DATETIME      DEFAULT NULL COMMENT '发布时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_category` (`category_id`),
    KEY `idx_status` (`status`, `deleted`),
    KEY `idx_publish_time` (`publish_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章';

-- -------------------------------------------------------------
-- 文章-标签关联
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_article_tag`
(
    `id`         BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id` BIGINT NOT NULL COMMENT '文章 ID',
    `tag_id`     BIGINT NOT NULL COMMENT '标签 ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_tag` (`article_id`, `tag_id`),
    KEY `idx_tag` (`tag_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章标签关联';

-- -------------------------------------------------------------
-- 评论
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_comment`
(
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`  BIGINT        NOT NULL COMMENT '文章 ID',
    `nickname`    VARCHAR(50)   NOT NULL COMMENT '昵称',
    `email`       VARCHAR(100)   DEFAULT NULL COMMENT '邮箱',
    `content`     VARCHAR(1000) NOT NULL COMMENT '评论内容',
    `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待审核 1 已通过',
    `ip`          VARCHAR(50)    DEFAULT NULL COMMENT '来源 IP',
    `user_agent`  VARCHAR(255)   DEFAULT NULL COMMENT 'UA',
    `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_article` (`article_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章评论';

-- -------------------------------------------------------------
-- 美食（探店记录）
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_food`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(100) NOT NULL COMMENT '店名',
    `cuisine`     VARCHAR(30)   DEFAULT NULL COMMENT '菜系，如 川菜 / 日料 / 火锅',
    `dishes`      VARCHAR(255)  DEFAULT NULL COMMENT '特色菜肴，多个用顿号或逗号分隔',
    `address`     VARCHAR(255)  DEFAULT NULL COMMENT '店位置（文字地址，用于展示与搜索）',
    `longitude`   DECIMAL(10, 6) DEFAULT NULL COMMENT '经度（gcj02 高德坐标）',
    `latitude`    DECIMAL(10, 6) DEFAULT NULL COMMENT '纬度（gcj02 高德坐标）',
    `poi_id`      VARCHAR(40)   DEFAULT NULL COMMENT '高德 POI ID，有它可不填经纬度',
    `avg_price`   INT           DEFAULT NULL COMMENT '人均价格（元）',
    `rating`      TINYINT       DEFAULT NULL COMMENT '推荐度 1-5 星',
    `cover`       VARCHAR(500)  DEFAULT NULL COMMENT '封面图',
    `remark`      VARCHAR(500)  DEFAULT NULL COMMENT '备注：招牌、排队情况、停车等',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 展示 0 隐藏',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `deleted`, `sort`),
    KEY `idx_cuisine` (`cuisine`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='美食探店记录';

-- -------------------------------------------------------------
-- 站点配置
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `blog_site_config`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(50) NOT NULL COMMENT '配置项',
    `config_value` TEXT COMMENT '配置值',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '说明',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='站点配置';

-- -------------------------------------------------------------
--  行情模块
--  与 04-migration-stock.sql 保持一致，改一处必须改另一处。
-- -------------------------------------------------------------

-- -------------------------------------------------------------
--  行情模块的键值配置
--
--  为什么需要它：桌面版的 AI Key、画像、刷新间隔等都存在本地文件 / localStorage，
--  而博客是多端访问，配置必须跟人走。这里用一张 key-value 表统一收口。
--
--  secret 列的意义：AI Key 属于敏感信息，读取接口默认不回传明文（只回传掩码），
--  避免 `/api/stock/config` 被爬或被截图时泄漏。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_setting`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置项',
    `config_value` TEXT COMMENT '配置值（JSON 或纯文本）',
    `is_secret`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=敏感值，读取时打码',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '说明',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='行情模块配置';

-- -------------------------------------------------------------
--  日K线历史（对照桌面版 kline 表）
--
--  用途：信号回测与历史胜率都要读「过去 60+ 根日K」。每次都向上游拉一遍
--  既慢又容易被限流，所以落库缓存。周期只存 day —— 分钟线时效性太强，
--  缓存了也没有复用价值。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_kline`
(
    `id`       BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`     VARCHAR(16) NOT NULL COMMENT '归一化代码，如 600519 / 00700',
    `period`   VARCHAR(10) NOT NULL DEFAULT 'day' COMMENT '周期，目前只用 day',
    `date`     VARCHAR(16) NOT NULL COMMENT '交易日，day 用 yyyy-MM-dd',
    `open`     DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '开',
    `high`     DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '高',
    `low`      DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '低',
    `close`    DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '收',
    `volume`   DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '成交量（手）',
    `turnover` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '成交额（万元）',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_period_date` (`code`, `period`, `date`),
    KEY `idx_code_period` (`code`, `period`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='日K线历史缓存';

-- -------------------------------------------------------------
--  信号事件（对照桌面版 signal_event 表）
--
--  这是「历史信号胜率」唯一的数据来源：信号发生时写一条，之后拿它跟
--  真实走出来的日K对照，算 1/3/5/10 日胜率。
--
--  unique(code,date,kind,name,time)：同一根K线同一信号只记一次，
--  重复扫描不会把胜率样本灌水。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_signal_event`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`       VARCHAR(16) NOT NULL COMMENT '归一化代码',
    `name`       VARCHAR(50) NOT NULL DEFAULT '' COMMENT '股票名称快照',
    `date`       VARCHAR(16) NOT NULL COMMENT '交易日 yyyy-MM-dd',
    `time`       VARCHAR(10) NOT NULL DEFAULT '' COMMENT '发生时刻 HH:mm，日线信号留空',
    `kind`       VARCHAR(32) NOT NULL DEFAULT '' COMMENT '信号族：backtest/ma/tech/volume/trap',
    `name_rule`  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '规则名，如 MA5×MA10金叉',
    `dir`        VARCHAR(8)  NOT NULL DEFAULT '' COMMENT '方向 bull/bear，空=未知不计胜率',
    `detail`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文字说明',
    `price`      DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '信号触发时价格',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_signal` (`code`, `date`, `kind`, `name_rule`, `time`),
    KEY `idx_code_date` (`code`, `date`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='历史信号事件';

-- -------------------------------------------------------------
--  提醒触发记录（对照桌面版 alert_event 表）
--
--  为什么要落库而不是只存内存：提醒要求「每股票每日每类型一次」，
--  而页面刷新会重建 composable。内存去重扛不住刷新，刷新完就重复弹。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_alert_event`
(
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`       VARCHAR(16) NOT NULL COMMENT '归一化代码',
    `name`       VARCHAR(50) NOT NULL DEFAULT '' COMMENT '股票名称快照',
    `date`       VARCHAR(16) NOT NULL COMMENT '交易日 yyyy-MM-dd',
    `kind`       VARCHAR(32) NOT NULL DEFAULT '' COMMENT '提醒类型：price/ma/position/watchlist',
    `message`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '通知正文',
    `price`      DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '触发时价格',
    `target`     DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '目标价/阈值',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert` (`code`, `date`, `kind`),
    KEY `idx_code_date` (`code`, `date`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='提醒触发记录';

-- -------------------------------------------------------------
--  自选变更流水（对照桌面版 watchlist_change 表）
--
--  桌面版写这张表是为了复盘「什么时候开始关注这只」。
--  博客的自选列表本身存在前端，这里只记变更事件，不影响功能可用性。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_watchlist_change`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `code`        VARCHAR(16) NOT NULL COMMENT '归一化代码',
    `name`        VARCHAR(50) NOT NULL DEFAULT '' COMMENT '股票名称快照',
    `action`      VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'add/remove',
    `change_date` VARCHAR(16) NOT NULL COMMENT '日期 yyyy-MM-dd',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_code_time` (`code`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='自选变更流水';

-- -------------------------------------------------------------
--  模拟盘（paper trading）
--  与 05-migration-paper.sql 保持一致，改一处必须改另一处。
--  口径：金额 DECIMAL(20,2)，价格 DECIMAL(16,4)，数量 INT（股），
--  日期 VARCHAR(16) 存 yyyy-MM-dd；所有查询按 account_id，索引从它开始。
-- -------------------------------------------------------------

-- 模拟账户：一个后台用户一个账户；auto_trade 为自动交易总开关
CREATE TABLE IF NOT EXISTS `paper_account`
(
    `id`               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT         NOT NULL COMMENT '后台用户 id',
    `name`             VARCHAR(64)    NOT NULL DEFAULT '模拟盘' COMMENT '账户名称',
    `initial_cash`     DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '初始资金',
    `cash`             DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '可用现金',
    `frozen_cash`      DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '冻结资金（保留字段）',
    `market_value`     DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '持仓市值',
    `total_asset`      DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '总资产 = 现金 + 市值',
    `total_profit`     DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '累计盈亏',
    `total_profit_pct` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '累计收益率（%）',
    `auto_trade`       TINYINT        NOT NULL DEFAULT 0 COMMENT '自动交易开关：0 关 1 开',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘账户';

-- 持仓：available_quantity 是 T+1 的载体（当天买入不可卖，收盘结算后转可用）
CREATE TABLE IF NOT EXISTS `paper_position`
(
    `id`                 BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`         BIGINT         NOT NULL COMMENT '账户 id',
    `code`               VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`               VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `quantity`           INT            NOT NULL DEFAULT 0 COMMENT '持仓数量（股）',
    `available_quantity` INT            NOT NULL DEFAULT 0 COMMENT '可卖数量（T+1）',
    `cost_price`         DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '摊薄成本价（含买入费用）',
    `last_price`         DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '最新价',
    `market_value`       DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '市值',
    `profit`             DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '浮动盈亏',
    `profit_pct`         DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '浮动盈亏率（%）',
    `open_date`          VARCHAR(16)    NOT NULL DEFAULT '' COMMENT '首次建仓日',
    `update_time`        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_code` (`account_id`, `code`),
    KEY `idx_account` (`account_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘持仓';

-- 委托：市价单立即成交；限价单挂 PENDING 等 tick 撮合
CREATE TABLE IF NOT EXISTS `paper_order`
(
    `id`              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`      BIGINT         NOT NULL COMMENT '账户 id',
    `code`            VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`            VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `side`            VARCHAR(8)     NOT NULL COMMENT 'BUY / SELL',
    `type`            VARCHAR(8)     NOT NULL DEFAULT 'MARKET' COMMENT 'MARKET / LIMIT',
    `price`           DECIMAL(16, 4)          DEFAULT NULL COMMENT '限价（市价单为空）',
    `quantity`        INT            NOT NULL COMMENT '委托数量（股）',
    `filled_quantity` INT            NOT NULL DEFAULT 0 COMMENT '已成交数量',
    `avg_price`       DECIMAL(16, 4)          DEFAULT NULL COMMENT '成交均价',
    `amount`          DECIMAL(20, 2)          DEFAULT NULL COMMENT '成交金额',
    `status`          VARCHAR(16)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/FILLED/CANCELLED/REJECTED',
    `reject_reason`   VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '拒单原因',
    `source`          VARCHAR(16)    NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / STRATEGY',
    `strategy_id`     BIGINT                  DEFAULT NULL COMMENT '策略 id（自动交易时）',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `filled_time`     DATETIME                DEFAULT NULL COMMENT '成交时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_status` (`account_id`, `status`),
    KEY `idx_account_time` (`account_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘委托';

-- 成交：费用三项分开存，便于展示与统计
CREATE TABLE IF NOT EXISTS `paper_trade`
(
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`   BIGINT         NOT NULL COMMENT '账户 id',
    `order_id`     BIGINT         NOT NULL COMMENT '委托 id',
    `code`         VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`         VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `side`         VARCHAR(8)     NOT NULL COMMENT 'BUY / SELL',
    `price`        DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '成交价',
    `quantity`     INT            NOT NULL DEFAULT 0 COMMENT '成交数量',
    `amount`       DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '成交金额（不含费用）',
    `commission`   DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '佣金',
    `stamp_tax`    DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '印花税（仅卖出）',
    `transfer_fee` DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '过户费',
    `total_fee`    DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '费用合计',
    `trade_date`   VARCHAR(16)    NOT NULL COMMENT '交易日 yyyy-MM-dd',
    `trade_time`   DATETIME       NOT NULL COMMENT '成交时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_date` (`account_id`, `trade_date`),
    KEY `idx_account_time` (`account_id`, `trade_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘成交';

-- 每日净值：收盘结算写一条，唯一键保证重复执行只更新不重复插
CREATE TABLE IF NOT EXISTS `paper_nav_daily`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`    BIGINT         NOT NULL COMMENT '账户 id',
    `trade_date`    VARCHAR(16)    NOT NULL COMMENT '交易日 yyyy-MM-dd',
    `cash`          DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '收盘现金',
    `market_value`  DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '收盘市值',
    `total_asset`   DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '收盘总资产',
    `day_pnl`       DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '当日盈亏',
    `day_pnl_pct`   DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '当日收益率（%）',
    `total_pnl`     DECIMAL(20, 2) NOT NULL DEFAULT 0 COMMENT '累计盈亏',
    `total_pnl_pct` DECIMAL(10, 4) NOT NULL DEFAULT 0 COMMENT '累计收益率（%）',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_date` (`account_id`, `trade_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘每日净值';

-- 策略：params 存 JSON 文本（止损、止盈、最大持仓数等），前端原样编辑
CREATE TABLE IF NOT EXISTS `paper_strategy`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`    BIGINT      NOT NULL COMMENT '账户 id',
    `name`          VARCHAR(64) NOT NULL DEFAULT '默认策略' COMMENT '策略名称',
    `type`          VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT '策略类型',
    `params`        TEXT COMMENT '参数 JSON',
    `enabled`       TINYINT     NOT NULL DEFAULT 0 COMMENT '策略开关：0 关 1 开',
    `last_run_time` DATETIME             DEFAULT NULL COMMENT '上次执行时间',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_account` (`account_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘策略';

-- 信号 / 选股结果：action = CANDIDATE / BUY / SELL
CREATE TABLE IF NOT EXISTS `paper_signal`
(
    `id`          BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`  BIGINT         NOT NULL COMMENT '账户 id',
    `strategy_id` BIGINT                  DEFAULT NULL COMMENT '策略 id',
    `code`        VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`        VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `action`      VARCHAR(16)    NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE / BUY / SELL',
    `reason`      VARCHAR(255)   NOT NULL DEFAULT '' COMMENT '信号理由',
    `score`       INT            NOT NULL DEFAULT 0 COMMENT '规则得分',
    `price`       DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '信号发生价格',
    `trade_date`  VARCHAR(16)    NOT NULL COMMENT '日期 yyyy-MM-dd',
    `create_time` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_date` (`account_id`, `trade_date`),
    KEY `idx_code_date` (`code`, `trade_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘信号与选股结果';
