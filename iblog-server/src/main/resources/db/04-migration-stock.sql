-- =============================================================
--  iblog 增量迁移：行情模块的持久化
--  说明：
--    1. 对照桌面版 stock-analysis-prh 的 SQLite 本地历史库（db.rs），
--       在 MySQL 里建同样职责的表。之所以不照搬浏览器 IndexedDB/localStorage：
--       清缓存就丢、换设备就丢，而信号胜率需要长期积累才有意义。
--    2. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--    3. 已并入 01-schema.sql，新库会直接带上这些表，不需要跑本脚本。
--  手工执行：
--    mysql -uroot -p iblog < 04-migration-stock.sql
--  容器里执行（线上）：
--    docker compose exec -T mysql mysql -uroot -p"$P" iblog < 04-migration-stock.sql
-- =============================================================

SET NAMES utf8mb4;

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
