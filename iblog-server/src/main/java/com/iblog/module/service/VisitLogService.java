package com.iblog.module.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iblog.module.entity.VisitLog;
import com.iblog.module.mapper.VisitLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 官网访问日志服务：落库、分页查询、统计、过期清理。
 */
@Slf4j
@Service
public class VisitLogService extends ServiceImpl<VisitLogMapper, VisitLog> {

    /** 访问日志保留天数，超过就删（个人博客，30 天足够回看）。 */
    private static final int RETENTION_DAYS = 30;

    /**
     * 后台分页查询，时间倒序。
     *
     * @param path 路径前缀过滤（如 /api/portal/articles），空查全部
     */
    public IPage<VisitLog> pageForAdmin(long pageNum, long pageSize, String path) {
        return page(new Page<>(pageNum, pageSize), new LambdaQueryWrapper<VisitLog>()
                .likeRight(path != null && !path.isBlank(), VisitLog::getPath, path)
                .orderByDesc(VisitLog::getId));
    }

    /**
     * 今日访问次数（PV 口径：API 调用次数）。
     */
    public long todayCount() {
        return count(new LambdaQueryWrapper<VisitLog>()
                .ge(VisitLog::getCreateTime, LocalDateTime.now().toLocalDate().atStartOfDay()));
    }

    /**
     * 今日独立 IP 数（UV 的近似口径：没有账号体系，按 IP 去重）。
     */
    public long todayIpCount() {
        List<Object> objs = listObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<VisitLog>()
                .select("COUNT(DISTINCT ip)")
                .ge("create_time", LocalDateTime.now().toLocalDate().atStartOfDay()));
        return objs.isEmpty() || objs.get(0) == null ? 0L : ((Number) objs.get(0)).longValue();
    }

    /**
     * 热门路径 TopN（今日）。
     *
     * <p>用 {@code selectMaps} 而不是 {@code listObjs}：后者底层是 selectObjs，
     * 只返回第一列，多列聚合时 count 会永远取不到。</p>
     *
     * @return 每项含 path 与 count 两个 key
     */
    public List<Map<String, Object>> todayTopPaths(int limit) {
        return baseMapper.selectMaps(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<VisitLog>()
                .select("path", "COUNT(*) AS cnt")
                .ge("create_time", LocalDateTime.now().toLocalDate().atStartOfDay())
                .groupBy("path")
                .orderByDesc("cnt")
                .last("limit " + Math.max(1, limit)));
    }

    /**
     * 每天凌晨清理过期日志。@Scheduled 依赖 IblogApplication 上的 @EnableScheduling（已开启）。
     */
    @Scheduled(cron = "0 30 3 * * ?", zone = "Asia/Shanghai")
    public void cleanExpired() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
        boolean removed = remove(new LambdaQueryWrapper<VisitLog>()
                .lt(VisitLog::getCreateTime, threshold));
        if (removed) {
            log.info("访问日志清理完成 threshold={}", threshold);
        }
    }
}
