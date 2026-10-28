-- =============================================================
--  iblog 增量迁移：官网访问日志
--  说明：
--    1. 记录官网对 /api/portal/** 的每次 API 访问（官网是 SPA，页面本身由
--       nginx 直接吐静态文件、不进 Spring；但每次页面打开必然触发
--       /api/portal/site 等接口调用，所以在 API 层记录即等价于记录访问）。
--    2. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--    3. 已并入 01-schema.sql，新库会直接带上这张表。
--  数据保留：由 VisitLogCleaner 每天凌晨清理 30 天前的记录，防止无限增长。
--  手工执行：
--    mysql -uroot -p iblog < 09-migration-visit-log.sql
-- =============================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `blog_visit_log`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `path`        VARCHAR(128) NOT NULL COMMENT '请求路径（不含 query）',
    `method`      VARCHAR(8)   NOT NULL DEFAULT 'GET' COMMENT 'HTTP 方法',
    `ip`          VARCHAR(45)  NOT NULL DEFAULT '' COMMENT '客户端 IP（取 X-Forwarded-For 首段）',
    `user_agent`  VARCHAR(255)          DEFAULT NULL COMMENT '浏览器 UA（截断 255）',
    `referer`     VARCHAR(255)          DEFAULT NULL COMMENT '来源页（截断 255）',
    `status`      INT          NOT NULL DEFAULT 200 COMMENT '响应业务码（R.code，200 为成功）',
    `cost_ms`     INT          NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
    PRIMARY KEY (`id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_path_time` (`path`, `create_time`),
    KEY `idx_ip_time` (`ip`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='官网访问日志';
