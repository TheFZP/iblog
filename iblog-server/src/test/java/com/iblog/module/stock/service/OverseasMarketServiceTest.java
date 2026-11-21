package com.iblog.module.stock.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverseasMarketServiceTest {

    private static final String EASTMONEY = """
            {"rc":0,"data":{"total":5,"diff":[
              {"f2":48147.33,"f3":-1.93,"f12":"DJIA","f14":"道琼斯"},
              {"f2":22617.73,"f3":-5.28,"f12":"NDX","f14":"纳斯达克"},
              {"f2":6910.39,"f3":-3.7,"f12":"SPX","f14":"标普500"},
              {"f2":56436.65,"f3":-5.06,"f12":"N225","f14":"日经225"},
              {"f2":4869.96,"f3":-5.21,"f12":"KS11","f14":"韩国KOSPI"}
            ]}}
            """;

    private static final String TENCENT = """
            v_usDJI="200~道琼斯~.DJI~48147.33~49097.06~49071.82~1167163269~0~0~47921.18~0~0~0~0~0~0~0~0~0~48576.70~0~0~0~0~0~0~0~0~0~~2026-11-20 16:35:59~-949.73~-1.93~49594.90~47998.86~USD";
            v_usIXIC="200~纳斯达克~.IXIC~22617.73~23879.20~23592.67~10597030699~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~0~~2026-11-20 17:15:59~-1261.47~-5.28~23837.76~22513.01~USD";
            v_usINX="200~标普500~.INX~6910.39~7175.83~7150.39~4727416323~0~0~6893.98~0~0~0~0~0~0~0~0~0~7026.48~0~0~0~0~0~0~0~0~0~~2026-11-20 16:35:54~-265.44~-3.70~7198.67~6882.66~USD";
            """;

    @Test
    void parsesEastmoneyFiveIndices() {
        OverseasMarketService.Snapshot snapshot = OverseasMarketService.parseEastmoney(EASTMONEY);

        assertTrue(snapshot.available());
        assertEquals("eastmoney", snapshot.source());
        assertEquals(5, snapshot.moves().size());
        assertEquals(-5.28, snapshot.moves().get("NDX"), 1e-9);
        // 权重按 NDX .30 / SPX .25 / DJIA .20 / N225 .15 / KS11 .10 归一
        double expected = -5.28 * .30 - 3.70 * .25 - 1.93 * .20 - 5.06 * .15 - 5.21 * .10;
        assertEquals(expected, snapshot.weightedPct(), 0.01);
    }

    @Test
    void parsesTencentFallback() {
        OverseasMarketService.Snapshot snapshot = OverseasMarketService.parseTencent(TENCENT);

        assertTrue(snapshot.available());
        assertEquals("tencent", snapshot.source());
        assertEquals(3, snapshot.moves().size());
        assertEquals(-1.93, snapshot.moves().get("DJIA"), 1e-9);
        assertEquals(-3.70, snapshot.moves().get("SPX"), 1e-9);
    }

    @Test
    void missingIndicesAreRenormalizedInsteadOfCountedAsZero() {
        Map<String, Double> onlyUs = new LinkedHashMap<>();
        onlyUs.put("NDX", 2.0);
        onlyUs.put("SPX", 1.0);

        // 不含日韩时不能用 0 填补，否则会被稀释成 1.2 左右
        assertEquals(1.55, OverseasMarketService.weighted(onlyUs), 0.01);
    }

    @Test
    void malformedBodiesReturnUnavailable() {
        assertFalse(OverseasMarketService.parseEastmoney("").available());
        assertFalse(OverseasMarketService.parseEastmoney("<html>404</html>").available());
        assertFalse(OverseasMarketService.parseTencent("v_usDJI=\"\";").available());
        assertFalse(OverseasMarketService.parseTencent("not a quote").available());
    }
}
