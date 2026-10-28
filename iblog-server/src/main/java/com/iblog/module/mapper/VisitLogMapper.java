package com.iblog.module.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.entity.VisitLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 官网访问日志 Mapper。
 */
@Mapper
public interface VisitLogMapper extends BaseMapper<VisitLog> {
}
