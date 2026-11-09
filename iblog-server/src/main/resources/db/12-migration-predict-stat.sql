-- 尾盘预测胜率统计：候选明细 + 当日回填。
-- 幂等（CREATE TABLE IF NOT EXISTS），可重复执行。
-- 语义：每次尾盘预测跑完把候选落一条；trade_date 收盘结算时回填 close_price/close_pct，
-- 并据此算「预测对了没有」。回填前 close_pct 为 NULL，表示该候选结果待定。
-- 「涨（close_pct > 0）记为命中」，close_pct = (close_price - predict_price)/predict_price*100。
CREATE TABLE IF NOT EXISTS `paper_predict_candidate`
(
    `id`            BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `account_id`    BIGINT         NOT NULL COMMENT '账户 id',
    `strategy_id`   BIGINT         NULL COMMENT '策略 id',
    `trade_date`    VARCHAR(16)    NOT NULL COMMENT '预测交易日 yyyy-MM-dd',
    `round_time`    VARCHAR(16)    NOT NULL COMMENT '预测轮次（14:30 / 14:50）',
    `code`          VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`          VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `score`         INT            NOT NULL DEFAULT 0 COMMENT '预测分',
    `reason`        VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '命中理由（原因前缀「尾盘预测：」）',
    `predict_price` DECIMAL(16, 4) NOT NULL DEFAULT 0 COMMENT '预测时现价',
    `close_price`   DECIMAL(16, 4) NULL COMMENT '当日收盘价（结算时回填）',
    `close_pct`     DECIMAL(8, 4)  NULL COMMENT '当日收盘涨幅%（涨>0记命中）',
    `hit`           TINYINT        NULL COMMENT '是否命中 1=涨 0=跌 2=平(NULL=待回填)',
    `create_time`   DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (`id`),
    KEY `idx_account_date` (`account_id`, `trade_date`),
    KEY `idx_date_code` (`trade_date`, `code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘尾盘预测候选与胜率明细';