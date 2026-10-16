package com.iblog.module.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 美食探店记录。
 *
 * <p>经纬度按高德（gcj02）坐标系存储 —— 官网「跳转高德」直接把它拼进 URI，
 * 存 wgs84 会偏几百米。没有经纬度时可以只填 {@code poiId}，同样能定位。</p>
 */
@Data
@TableName("blog_food")
public class Food {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 店名 */
    private String name;

    /** 菜系，如 川菜 / 日料 / 火锅 */
    private String cuisine;

    /** 特色菜肴，多个用顿号或逗号分隔 */
    private String dishes;

    /** 店位置（文字地址） */
    private String address;

    /** 经度（gcj02） */
    private BigDecimal longitude;

    /** 纬度（gcj02） */
    private BigDecimal latitude;

    /** 高德 POI ID，有它可不填经纬度 */
    private String poiId;

    /** 人均价格（元） */
    private Integer avgPrice;

    /** 推荐度 1-5 星 */
    private Integer rating;

    /** 封面图 */
    private String cover;

    /** 备注 */
    private String remark;

    /** 最近一次去的时间 */
    private LocalDate visitedAt;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 1 展示 0 隐藏 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
