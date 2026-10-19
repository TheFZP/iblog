package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行情模块的键值配置。
 *
 * <p>对照桌面版 stock-analysis-prh 的「设置存本地文件」做法，这里改为存 MySQL ——
 * 博客是多端访问，AI Key 与设置项必须跟人走，清缓存或换设备不该丢掉配置。</p>
 *
 * <p>本表故意<b>不加</b> {@code @TableLogic}：配置项删除就是真删，
 * 留着逻辑删除的残行会让 {@code uk_config_key} 唯一索引挡住同名项重建。</p>
 */
@Data
@TableName("stock_setting")
public class StockSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置项，如 ai_api_key / ai_model / quotes_refresh_ms */
    private String configKey;

    /** 配置值（JSON 或纯文本） */
    private String configValue;

    /** 1=敏感值，读取接口只回传掩码 */
    private Integer isSecret;

    /** 说明 */
    private String remark;

    private LocalDateTime updateTime;
}
