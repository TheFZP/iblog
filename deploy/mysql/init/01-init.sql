-- =============================================================
--  iblog 个人博客 数据库初始化脚本
--  MySQL 8.0+ ，字符集 utf8mb4
--  说明：管理员账号（admin / admin123）由后端启动时自动初始化，
--        密文用 BCrypt 在运行时生成，因此这里不写死密码。
-- =============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `iblog`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `iblog`;

-- -------------------------------------------------------------
-- 后台用户
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`
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
DROP TABLE IF EXISTS `blog_category`;
CREATE TABLE `blog_category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `slug`        VARCHAR(50) NOT NULL COMMENT '英文别名',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '描述',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章分类';

-- -------------------------------------------------------------
-- 标签
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `blog_tag`;
CREATE TABLE `blog_tag`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(50) NOT NULL COMMENT '标签名称',
    `slug`        VARCHAR(50) NOT NULL COMMENT '英文别名',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序值',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文章标签';

-- -------------------------------------------------------------
-- 文章
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `blog_article`;
CREATE TABLE `blog_article`
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
DROP TABLE IF EXISTS `blog_article_tag`;
CREATE TABLE `blog_article_tag`
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
DROP TABLE IF EXISTS `blog_comment`;
CREATE TABLE `blog_comment`
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
-- 站点配置
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `blog_site_config`;
CREATE TABLE `blog_site_config`
(
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(50) NOT NULL COMMENT '配置项',
    `config_value` TEXT COMMENT '配置值',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '说明',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='站点配置';

-- =============================================================
--  种子数据
-- =============================================================

INSERT INTO `blog_category` (`id`, `name`, `slug`, `description`, `sort`)
VALUES (1, '技术笔记', 'tech', 'Java、前端、数据库等后端开发记录', 1),
       (2, '项目复盘', 'project', '做过的项目与踩坑总结', 2),
       (3, '读书笔记', 'reading', '技术书与非技术书的阅读笔记', 3),
       (4, '生活随笔', 'life', '工作之外的一些记录', 4);

INSERT INTO `blog_tag` (`id`, `name`, `slug`, `sort`)
VALUES (1, 'Java', 'java', 1),
       (2, 'Spring Boot', 'spring-boot', 2),
       (3, 'Vue', 'vue', 3),
       (4, 'MySQL', 'mysql', 4),
       (5, 'Redis', 'redis', 5),
       (6, '运维部署', 'devops', 6);

INSERT INTO `blog_article` (`id`, `title`, `slug`, `summary`, `content`, `category_id`, `author`, `status`,
                            `is_top`, `view_count`, `comment_count`, `publish_time`)
VALUES (1, 'iblog 项目初始化完成', 'iblog-init',
        '一套前后端分离的个人博客骨架：Spring Boot 3 + Vue3 + MySQL + Redis。',
        '## 欢迎使用 iblog\n\n这是一篇示例文章，你可以在后台「文章管理」中编辑或删除它。\n\n### 技术栈\n\n- 后端：Java 17 + Spring Boot 3.3 + MyBatis-Plus\n- 数据库：MySQL 8\n- 缓存：Redis（登录令牌、站点配置）\n- 后台管理：Vue3 + Vite + Element Plus\n- 官网前台：Vue3 + Vite\n\n### 下一步\n\n1. 登录管理后台，修改默认密码\n2. 在「站点设置」里完善站点信息\n3. 开始写你的第一篇文章',
        2, 'admin', 1, 1, 0, 0, NOW()),
       (2, '为什么用 Redis 做登录态', 'why-redis-session',
        'JWT 负责签名与过期，Redis 负责可撤销，二者结合兼顾性能与安全。',
        '## JWT + Redis 的组合\n\nJWT 本身是无状态的，签发之后服务端无法主动让它失效。\n\n所以这里做了一个折中：\n\n- JWT 携带用户标识与过期时间，负责无状态校验\n- Redis 保存 `用户ID -> 令牌` 的映射，负责「可主动注销」\n\n退出登录时只需删除 Redis 中的键，令牌立即失效。',
        1, 'admin', 1, 0, 0, 0, NOW());

INSERT INTO `blog_article_tag` (`article_id`, `tag_id`)
VALUES (1, 2), (1, 3), (1, 4), (1, 5),
       (2, 1), (2, 5);

INSERT INTO `blog_site_config` (`config_key`, `config_value`, `remark`)
VALUES ('site_title', 'iblog', '站点标题'),
       ('site_subtitle', '记录技术与生活', '站点副标题'),
       ('site_description', '一个用 Spring Boot 3 + Vue3 搭建的个人博客', '站点描述'),
       ('site_keywords', '个人博客,Java,Spring Boot,Vue', 'SEO 关键词'),
       ('site_footer', 'Powered by iblog', '页脚文案'),
       ('site_icp', '', '备案号'),
       ('author_name', '博主', '作者名称'),
       ('author_avatar', '', '作者头像地址'),
       ('author_intro', '一个正在认真写代码的人。', '作者简介'),
       ('github_url', '', 'GitHub 地址'),
       ('email', 'admin@iblog.com', '联系邮箱'),
       ('comment_enabled', '1', '是否开放评论：1 开 0 关'),
       ('page_size', '10', '列表默认每页条数');
