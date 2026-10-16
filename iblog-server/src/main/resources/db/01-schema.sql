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
    `visited_at`  DATE          DEFAULT NULL COMMENT '最近一次去的时间',
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
