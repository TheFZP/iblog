-- =============================================================
--  iblog 种子数据（可重复执行）
--  说明：
--    1. 全部使用 INSERT IGNORE + 显式主键，重复执行不会产生脏数据；
--    2. 依赖 01-schema.sql 先执行；
--    3. 已有数据时新增的 id 不会冲突，也不会覆盖你自己写的内容。
--  手工执行：mysql -uroot -p iblog < 02-data.sql
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 分类
-- -------------------------------------------------------------
INSERT IGNORE INTO `blog_category` (`id`, `name`, `slug`, `description`, `sort`)
VALUES (1, '技术笔记', 'tech', 'Java、前端、数据库等后端开发记录', 1),
       (2, '项目复盘', 'project', '做过的项目与踩坑总结', 2),
       (3, '读书笔记', 'reading', '技术书与非技术书的阅读笔记', 3),
       (4, '生活随笔', 'life', '工作之外的一些记录', 4);

-- -------------------------------------------------------------
-- 标签
-- -------------------------------------------------------------
INSERT IGNORE INTO `blog_tag` (`id`, `name`, `slug`, `sort`)
VALUES (1, 'Java', 'java', 1),
       (2, 'Spring Boot', 'spring-boot', 2),
       (3, 'Vue', 'vue', 3),
       (4, 'MySQL', 'mysql', 4),
       (5, 'Redis', 'redis', 5),
       (6, '运维部署', 'devops', 6);

-- -------------------------------------------------------------
-- 示例文章
-- -------------------------------------------------------------
INSERT IGNORE INTO `blog_article` (`id`, `title`, `slug`, `summary`, `content`, `category_id`, `author`, `status`,
                                   `is_top`, `view_count`, `comment_count`, `publish_time`)
VALUES (1, 'iblog 项目初始化完成', 'iblog-init',
        '一套前后端分离的个人博客骨架：Spring Boot 3 + Vue3 + MySQL + Redis。',
        '## 欢迎使用 iblog\n\n这是一篇示例文章，你可以在后台「文章管理」中编辑或删除它。\n\n### 技术栈\n\n- 后端：Java 17 + Spring Boot 3.3 + MyBatis-Plus\n- 数据库：MySQL 8\n- 缓存：Redis（登录令牌、站点配置）\n- 后台管理：Vue3 + Vite + Element Plus\n- 官网前台：Vue3 + Vite\n\n### 下一步\n\n1. 登录管理后台，修改默认密码\n2. 在「站点设置」里完善站点信息\n3. 开始写你的第一篇文章',
        2, 'admin', 1, 1, 0, 0, NOW()),
       (2, '为什么用 Redis 做登录态', 'why-redis-session',
        'JWT 负责签名与过期，Redis 负责可撤销，二者结合兼顾性能与安全。',
        '## JWT + Redis 的组合\n\nJWT 本身是无状态的，签发之后服务端无法主动让它失效。\n\n所以这里做了一个折中：\n\n- JWT 携带用户标识与过期时间，负责无状态校验\n- Redis 保存 `用户ID -> 令牌` 的映射，负责「可主动注销」\n\n退出登录时只需删除 Redis 中的键，令牌立即失效。',
        1, 'admin', 1, 0, 0, 0, NOW());

-- -------------------------------------------------------------
-- 文章-标签关联
-- -------------------------------------------------------------
INSERT IGNORE INTO `blog_article_tag` (`id`, `article_id`, `tag_id`)
VALUES (1, 1, 2), (2, 1, 3), (3, 1, 4), (4, 1, 5),
       (5, 2, 1), (6, 2, 5);

-- -------------------------------------------------------------
-- 站点配置
-- -------------------------------------------------------------
INSERT IGNORE INTO `blog_site_config` (`config_key`, `config_value`, `remark`)
VALUES ('site_title', 'iblog', '站点标题'),
       ('site_subtitle', '记录技术与生活', '站点副标题'),
       ('site_description', '一个用 Spring Boot 3 + Vue3 搭建的个人博客', '站点描述'),
       ('site_keywords', '个人博客,Java,Spring Boot,Vue', 'SEO 关键词'),
       ('site_footer', 'Powered by iblog', '页脚文案'),
       ('site_icp', '', '备案号'),
       ('author_name', '博主', '作者名称'),
       ('author_avatar', '', '作者头像地址'),
       ('author_intro', '一个正在认真让AI写代码的人。', '作者简介'),
       ('github_url', '', 'GitHub 地址'),
       ('email', 'admin@iblog.com', '联系邮箱'),
       ('comment_enabled', '1', '是否开放评论：1 开 0 关'),
       ('page_size', '10', '列表默认每页条数');
