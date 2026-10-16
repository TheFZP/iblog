-- =============================================================
--  iblog 增量迁移：美食栏目
--  说明：
--    1. 01-schema.sql 只在 MySQL 数据卷**首次初始化**时被 docker-entrypoint
--       执行；线上库已有数据，重新部署不会重跑它。所以新增表必须单独出一份
--       可重复执行的迁移脚本，手工对已有库执行。
--    2. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--    3. 已并入 01-schema.sql，新库会直接带上这张表，不需要跑本脚本。
--  手工执行：
--    mysql -uroot -p iblog < 03-migration-food.sql
--  容器里执行（线上）：
--    docker compose exec -T mysql mysql -uroot -p"$P" iblog < 03-migration-food.sql
-- =============================================================

SET NAMES utf8mb4;

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
-- 已有 blog_food 表的库：删掉不再使用的 visited_at 列
-- MySQL 8.0 不支持 DROP COLUMN IF EXISTS，用 information_schema 判断后动态执行。
-- 这段可重复执行。
-- -------------------------------------------------------------
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'blog_food' AND column_name = 'visited_at'
);
SET @sql := IF(@has_col > 0, 'ALTER TABLE `blog_food` DROP COLUMN `visited_at`', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
