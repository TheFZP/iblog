-- 全市场「次日看涨」预测：每日预测结果 + 新闻原文存档。
-- 幂等（CREATE TABLE IF NOT EXISTS），可重复执行。
--
-- stock_predict_daily：收盘后（15:30）全市场扫描的最终榜单，全局一份，不按账户。
--   预测口径：T 日收盘后预测 T+1 日上涨；
--   主指标 hit 用可交易口径（T+1 开盘买入 → T+1 收盘卖出收益 > 0），
--   hit_close 同时记录 T+1 收盘 vs T 收盘。未回填前 eval_status=PENDING。
--
-- stock_news_article：参与预测的新闻原文（标题/链接/来源/发布时间），
--   用于后续回测与人工核对；URL 用 MD5 建唯一键，避免长 URL 无法索引。
CREATE TABLE IF NOT EXISTS `stock_predict_daily`
(
    `id`                    BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trade_date`            DATE           NOT NULL COMMENT '预测生成日（T 日）',
    `rank_no`               INT            NOT NULL DEFAULT 0 COMMENT '榜单排名，从 1 开始',
    `code`                  VARCHAR(16)    NOT NULL COMMENT '归一化代码',
    `name`                  VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '股票名称',
    `score`                 INT            NOT NULL DEFAULT 0 COMMENT '最终分数 0~100（含新闻加减分）',
    `raw_score`             DECIMAL(6, 2)  NOT NULL DEFAULT 0 COMMENT '规则原始分',
    `max_score`             INT            NOT NULL DEFAULT 100 COMMENT '原始分满分（用于归一化）',
    `probability`           DECIMAL(5, 2)  NOT NULL DEFAULT 0 COMMENT '次日上涨参考概率 %',
    `prob_source`           VARCHAR(16)    NOT NULL DEFAULT 'HEURISTIC' COMMENT 'HEURISTIC / CALIBRATED',
    `calibration_n`         INT            NOT NULL DEFAULT 0 COMMENT '校准使用的历史样本数',
    `reason`                VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '命中理由（顿号分隔）',
    `model_version`         VARCHAR(32)    NOT NULL DEFAULT 'rule-v1' COMMENT '打分模型版本',
    `news_summary`          VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '新闻结论摘要',
    `news_score`            INT            NOT NULL DEFAULT 0 COMMENT '新闻情绪分 -100~100',
    `news_confidence`       DECIMAL(3, 2)  NOT NULL DEFAULT 0 COMMENT '新闻置信度 0~1',
    `news_sources`          MEDIUMTEXT COMMENT '新闻来源 JSON：[{title,url,source,publishedAt,scope}]',
    `features`              MEDIUMTEXT COMMENT '因子快照 JSON（为后续模型训练留数据）',
    `market_sentiment`      DECIMAL(5, 2)  NULL COMMENT '预测时市场情绪分',
    `prev_close`            DECIMAL(12, 3) NULL COMMENT 'T 日收盘价',
    `change_pct`            DECIMAL(7, 2)  NULL COMMENT 'T 日涨跌幅 %',
    `turnover_rate`         DECIMAL(7, 2)  NULL COMMENT 'T 日换手率 %',
    `amount_wan`            DECIMAL(16, 2) NULL COMMENT 'T 日成交额（万元）',
    `limit_up_at_close`     TINYINT        NOT NULL DEFAULT 0 COMMENT 'T 日收盘是否涨停 1=是',
    `buyable`               TINYINT        NOT NULL DEFAULT 1 COMMENT '是否可买 1=是 0=否',
    `next_trade_date`       DATE           NULL COMMENT '实际评估的 T+1 交易日',
    `next_open`             DECIMAL(12, 3) NULL COMMENT 'T+1 开盘价（后复权口径以行情源为准）',
    `next_close`            DECIMAL(12, 3) NULL COMMENT 'T+1 收盘价',
    `next_return_pct`       DECIMAL(8, 2)  NULL COMMENT 'T+1 收盘 vs T 收盘 %',
    `next_open_return_pct`  DECIMAL(8, 2)  NULL COMMENT 'T+1 开盘买 → 收盘卖 %（主指标）',
    `next_day_change_pct`   DECIMAL(8, 2)  NULL COMMENT 'T+1 当日涨跌幅 %',
    `next_limit_up_at_open` TINYINT        NULL COMMENT 'T+1 开盘是否涨停 1=是',
    `hit`                   TINYINT        NULL COMMENT '可交易口径是否命中 1=是 0=否（NULL=待回填）',
    `hit_close`             TINYINT        NULL COMMENT '收盘对收盘是否上涨 1=是 0=否（NULL=待回填）',
    `eval_status`           VARCHAR(16)    NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / EVALUATED / NO_DATA',
    `eval_retry`            INT            NOT NULL DEFAULT 0 COMMENT '回填重试次数',
    `create_time`           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    `update_time`           DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_code` (`trade_date`, `code`),
    KEY `idx_date_rank` (`trade_date`, `rank_no`),
    KEY `idx_date_prob` (`trade_date`, `probability`),
    KEY `idx_next_eval` (`next_trade_date`, `eval_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='全市场次日看涨预测（每日榜单）';

CREATE TABLE IF NOT EXISTS `stock_news_article`
(
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trade_date`      DATE          NOT NULL COMMENT '抓取所属交易日',
    `code`            VARCHAR(16)   NOT NULL COMMENT '归一化代码',
    `name`            VARCHAR(50)   NOT NULL DEFAULT '' COMMENT '股票名称',
    `title`           VARCHAR(300)  NOT NULL DEFAULT '' COMMENT '新闻标题',
    `url`             VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '原文链接（仅 http/https）',
    `url_hash`        CHAR(32)      NOT NULL COMMENT 'MD5(url)，用于唯一键',
    `source`          VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '来源站点',
    `published_at`    DATETIME      NULL COMMENT '发布时间',
    `snippet`         VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '摘要片段',
    `scope`           VARCHAR(16)   NOT NULL DEFAULT 'stock' COMMENT 'stock / industry / market',
    `sentiment_score` INT           NULL COMMENT '该条新闻情绪分 -100~100',
    `batch`           VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '所属扫描批次标识',
    `fetched_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抓取时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_date_code_url` (`trade_date`, `code`, `url_hash`),
    KEY `idx_code_time` (`code`, `fetched_at`),
    KEY `idx_date_scope` (`trade_date`, `scope`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='预测用新闻原文存档';
