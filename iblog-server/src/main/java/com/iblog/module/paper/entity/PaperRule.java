package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟盘规则模板：用户级的多套命名规则，params 与 paper_strategy.params 同构。
 * 应用模板时把 JSON 复制到账户当前策略，账户之后仍可单独微调。
 */
@Data
@TableName("paper_rule")
public class PaperRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** 参数 JSON 文本（22 项，与 PaperStrategyParams 对应） */
    private String params;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
