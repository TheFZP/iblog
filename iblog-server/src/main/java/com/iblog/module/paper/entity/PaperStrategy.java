package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟盘策略。params 存 JSON 文本（止损/止盈/最大持仓数等），前端原样编辑。
 */
@Data
@TableName("paper_strategy")
public class PaperStrategy {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    private String name;

    /** 策略类型，当前只有 AUTO */
    private String type;

    /** 参数 JSON 文本 */
    private String params;

    /** 策略开关：0 关 1 开 */
    private Integer enabled;

    private LocalDateTime lastRunTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
