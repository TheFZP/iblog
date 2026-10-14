package com.iblog.module.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.entity.Article;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
