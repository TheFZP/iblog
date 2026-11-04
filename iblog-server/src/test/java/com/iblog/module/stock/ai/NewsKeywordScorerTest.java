package com.iblog.module.stock.ai;

import com.iblog.module.stock.ai.AiWebSearchService.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsKeywordScorerTest {

    private final NewsKeywordScorer scorer = new NewsKeywordScorer();

    @Test
    void hardBadHitDragsToNegative() {
        List<SearchHit> hits = List.of(hit("公司被证监会立案调查", "证券时报"));
        assertTrue(scorer.scoreAll(hits) <= -60);
    }

    @Test
    void positiveFromOfficialSource() {
        List<SearchHit> hits = List.of(hit("中标 5 亿大单，业绩预增", "财联社"));
        assertTrue(scorer.scoreAll(hits) >= 20);
    }

    @Test
    void pureEarningsReportIsNeutral() {
        // 财报公告本身不偏多也不偏空
        List<SearchHit> hits = List.of(hit("公司发布 2025 年年度报告", "交易所"));
        assertTrue(scorer.scoreAll(hits) > -60 && scorer.scoreAll(hits) < 60);
    }

    @Test
    void rumorFromForumsDampened() {
        // 股吧小作文利好会被来源权重压低，但不应判成反向利空
        List<SearchHit> hits = List.of(hit("重大重组在途，复牌一字板", "股吧"));
        int score = scorer.scoreAll(hits);
        assertTrue(score < 60);
    }

    @Test
    void multipleBadIsMoreNegative() {
        List<SearchHit> hits = List.of(hit("业绩暴雷大额计提", "证券时报"), hit("股东清仓式减持", "东方财富"));
        assertTrue(scorer.scoreAll(hits) <= -60);
    }

    private static SearchHit hit(String title, String source) {
        return new SearchHit(title, "http://example.com", "", "", source);
    }
}