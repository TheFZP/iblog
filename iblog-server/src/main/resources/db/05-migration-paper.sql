-- =============================================================
--  iblog 模拟盘（paper trading）表结构
--  与 01-schema.sql 中的同名段落保持一致，改一处必须改另一处。
--
--  为什么放在 05：04 已经把行情配置/日K缓存/信号/提醒建好了，
--  模拟盘是行情之上的「交易层」，新增表单独一个迁移脚本，已有库
--  启动时会由 DatabaseBootstrap 自动补跑（全部 CREATE TABLE IF NOT EXISTS）。
--
--  口径说明：
--    1. 金额 DECIMAL(20,2)，价格 DECIMAL(16,4)，数量 INT（股）；
--    2. 日期用 VARCHAR(16) 存 yyyy-MM-dd，与 stock_* 表一致，便于字符串比较；
--    3. 所有表都按 account_id 查询，所以索引从 account_id 开始。
--  手工执行：mysql -uroot -p iblog < 05-migration-paper.sql
-- =============================================================
SET NAMES utf8mb4;

-- -------------------------------------------------------------
--  模拟账户
--
--  一个后台用户一个账户（uk_user），开户时按配置写入初始资金。
--  auto_trade 是「自动交易」总开关，默认关：策略先观察，用户手动打开。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  持仓
--
--  available_quantity 是 T+1 的载体：当天买入只增加 quantity，不增加可用；
--  每天收盘结算时把 quantity 抄成 available_quantity，第二天才能卖。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  委托
--
--  市价单立即成交（status=FILLED），限价单挂 PENDING 等 tick 撮合，
--  收盘未成交的由结算任务置 CANCELLED。reject_reason 保留拒单原因
--  （资金不足、涨跌停、非交易时段等），便于前端直接展示。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  成交（交割单）
--  费用三项分开存，方便前端展示和后续统计。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  每日净值快照
--
--  收盘结算时写一条，用于今日/历史收益、净值曲线、最大回撤。
--  uk(account_id, trade_date) 保证重复执行只更新不重复插。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  策略（自动选股 / 自动交易）
--  params 存 JSON 文本（止损、止盈、最大持仓数等），前端原样编辑。
-- -------------------------------------------------------------
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

-- -------------------------------------------------------------
--  信号 / 选股结果
--  action: CANDIDATE（选股候选）/ BUY（买入）/ SELL（卖出）
--  reason 存人类可读理由，score 存规则总分，复盘时可直接展示。
-- -------------------------------------------------------------
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
