-- =============================================================
--  iblog 增量迁移：AI 对话历史
--  说明：
--    1. 之前 AI 对话只存在浏览器内存里，刷新页面即丢。这里按「用户 + 股票」
--       落一份会话，官网刷新、换设备都能接着看之前的分析结论。
--    2. 全局 AI 助手（不绑定个股）用 stock_code = '' 表示，避免多建一张表。
--    3. 全部使用 CREATE TABLE IF NOT EXISTS，重复执行安全。
--    4. 已并入 01-schema.sql，新库会直接带上这些表。
--  数据保留：每个会话只保留最近 200 条消息，由 AiChatHistoryService 在写入后裁剪。
--  手工执行：
--    mysql -uroot -p iblog < 10-migration-ai-chat.sql
-- =============================================================
SET NAMES utf8mb4;

-- -------------------------------------------------------------
--  AI 会话：一个用户在一只股票上只有一条，全局对话用空 stock_code
--
--  为什么按股票而不是每次打开新建会话：用户看盘时是「今天问两句、明天接着问」，
--  按股票归档才符合这个心智；真要重来，面板上有「清空」。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_ai_chat_session`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      NOT NULL COMMENT '所属用户',
    `stock_code`  VARCHAR(16) NOT NULL DEFAULT '' COMMENT '归一化代码，空串=全局对话',
    `stock_name`  VARCHAR(50) NOT NULL DEFAULT '' COMMENT '股票名称快照',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_stock` (`user_id`, `stock_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 对话会话';

-- -------------------------------------------------------------
--  AI 消息：一问一答各一条，assistant 额外带思考过程、工具轨迹与选股卡片
--
--  tools / picks 存 JSON 文本而不是拆表：它们只用于还原界面，
--  不需要检索或关联，拆表反而多两次查询。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `stock_ai_chat_message`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT      NOT NULL COMMENT '会话 id',
    `role`        VARCHAR(16) NOT NULL COMMENT 'user / assistant',
    `content`     MEDIUMTEXT  NOT NULL COMMENT '正文',
    `reasoning`   MEDIUMTEXT COMMENT '思考过程（assistant 才有）',
    `tools`       MEDIUMTEXT COMMENT '工具轨迹 JSON：[{"name":"...","args":{...}}]',
    `picks`       MEDIUMTEXT COMMENT '选股卡片 JSON',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_session` (`session_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AI 对话消息';
