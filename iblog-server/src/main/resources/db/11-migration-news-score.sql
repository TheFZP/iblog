-- 新闻情绪分：选股「新闻资讯分析」因子落库缓存。
-- 幂等（CREATE TABLE IF NOT EXISTS），可重复执行。
-- 语义：score -100~100（正为利好负为利空）、confidence 0~1（官方披露高 / 传闻低）。
CREATE TABLE IF NOT EXISTS `stock_news_score` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `trade_date` DATE NOT NULL COMMENT '交易日',
  `code` VARCHAR(16) NOT NULL COMMENT '归一化代码',
  `name` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '股票名称',
  `score` INT NOT NULL DEFAULT 0 COMMENT '新闻情绪分 -100~100',
  `confidence` DECIMAL(3,2) NOT NULL DEFAULT 0 COMMENT '置信度 0~1',
  `label` VARCHAR(16) NOT NULL DEFAULT '中性' COMMENT '利好 / 中性 / 利空',
  `tags` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '话题标签逗号分隔',
  `summary` VARCHAR(500) NOT NULL DEFAULT '' COMMENT '一句结论摘要',
  `headlines` MEDIUMTEXT COMMENT '命中新闻标题/来源/时间 JSON',
  `source_count` INT NOT NULL DEFAULT 0 COMMENT '参与评分的新闻条数',
  `model` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '打分方式：ai / keyword / 空',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_date_code` (`trade_date`, `code`),
  KEY `idx_code_time` (`code`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='个股新闻情绪分';