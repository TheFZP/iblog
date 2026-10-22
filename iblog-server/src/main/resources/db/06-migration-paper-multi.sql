-- -------------------------------------------------------------
--  模拟盘多账户 + 策略规则参数化
--  背景：原来 paper_account 有 uk_user(user_id)，一个后台用户只能有一个
--  模拟盘；多开需要放开该唯一键，并给账户加展示排序字段。
--  规则参数化把打分权重/阈值放进 paper_strategy.params（JSON），
--  因此一个账户只保留一条策略记录（uk_account）。
--
--  本脚本幂等：可重复执行；新库由 01-schema.sql 直接建好，不需要跑。
--  执行方式：
--    mysql -uroot -p iblog < 06-migration-paper-multi.sql
-- -------------------------------------------------------------
SET NAMES utf8mb4;

-- 1) 去掉 paper_account 的 user_id 唯一键（旧库 uk_user），换成普通索引
SET @has_uk_user := (SELECT COUNT(*)
                     FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'paper_account'
                       AND INDEX_NAME = 'uk_user');
SET @sql := IF(@has_uk_user > 0, 'ALTER TABLE `paper_account` DROP INDEX `uk_user`', 'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx_user := (SELECT COUNT(*)
                      FROM information_schema.STATISTICS
                      WHERE TABLE_SCHEMA = DATABASE()
                        AND TABLE_NAME = 'paper_account'
                        AND INDEX_NAME = 'idx_user');
SET @sql := IF(@has_idx_user = 0, 'ALTER TABLE `paper_account` ADD INDEX `idx_user` (`user_id`)', 'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) paper_account 增加展示排序字段
SET @has_sort_no := (SELECT COUNT(*)
                     FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'paper_account'
                       AND COLUMN_NAME = 'sort_no');
SET @sql := IF(@has_sort_no = 0,
               'ALTER TABLE `paper_account` ADD COLUMN `sort_no` INT NOT NULL DEFAULT 0 COMMENT ''展示排序'' AFTER `name`',
               'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) paper_strategy：一个账户一套规则
--    极端情况下（历史手工数据）同一账户可能有多条策略，先只保留 id 最小的那条，
--    否则下面的唯一键会创建失败。
DELETE s FROM `paper_strategy` s
  JOIN `paper_strategy` t
    ON s.account_id = t.account_id AND s.id > t.id;

SET @has_uk_account := (SELECT COUNT(*)
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'paper_strategy'
                          AND INDEX_NAME = 'uk_account');
SET @sql := IF(@has_uk_account = 0,
               'ALTER TABLE `paper_strategy` ADD UNIQUE KEY `uk_account` (`account_id`)',
               'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
