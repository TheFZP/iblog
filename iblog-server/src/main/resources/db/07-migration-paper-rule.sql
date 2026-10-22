-- -------------------------------------------------------------
--  模拟盘规则模板（多套命名规则，一键应用到任意账户）
--  背景：paper_strategy 代表账户「当前生效」的一套参数；
--  想在同一账户上反复切换规则，需要把规则存成模板再 apply。
--  本脚本幂等：可重复执行；新库由 01-schema.sql 直接建好。
--  执行方式：
--    mysql -uroot -p iblog < 07-migration-paper-rule.sql
-- -------------------------------------------------------------
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `paper_rule`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT      NOT NULL COMMENT '所属用户',
    `name`        VARCHAR(64) NOT NULL COMMENT '模板名称',
    `params`      TEXT        NOT NULL COMMENT '策略参数 JSON（22 项）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='模拟盘规则模板';
