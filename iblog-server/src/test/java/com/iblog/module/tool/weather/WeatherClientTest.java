package com.iblog.module.tool.weather;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 天气解析的纯函数测试。
 *
 * <p>上游返回的是 JS 片段和「高温 26℃」这种带单位的字符串，
 * 解析错了页面不会报错，只会安静地展示错误温度，所以这里逐字段断言。</p>
 */
class WeatherClientTest {

    @Test
    void citySearchSkipsScenicSpotsAndPicksExactCity() {
        String body = """
                ([{"ref":"10128010103A~guangdong~广州抽水蓄能电厂旅游度假区~Resort~Resort~null~510000~null~广东省景点"},{"ref":"101280101~guangdong~广州~Guangzhou~广州~Guangzhou~20~510000~GZ~广东"}])""";
        WeatherClient.Location location = WeatherClient.parseCitySearch(body, "广州");
        assertNotNull(location);
        assertEquals("101280101", location.code());
        assertEquals("广州", location.city());
        assertEquals("广东", location.province());
    }

    @Test
    void citySearchPrefersExactNameOverFirstResult() {
        String body = """
                ([{"ref":"101280801~guangdong~广州南站~South Station~广州南站~null~510000~null~广东"},{"ref":"101280101~guangdong~广州~Guangzhou~广州~Guangzhou~20~510000~GZ~广东"}])""";
        WeatherClient.Location location = WeatherClient.parseCitySearch(body, "广州市");
        assertNotNull(location);
        assertEquals("广州", location.city());
    }

    @Test
    void citySearchReturnsNullOnGarbage() {
        assertNull(WeatherClient.parseCitySearch("404 not found", "广州"));
        assertNull(WeatherClient.parseCitySearch("([ ])", "广州"));
        assertNull(WeatherClient.parseCitySearch(null, "广州"));
    }

    @Test
    void parseForecastReadsNowAndDaily() {
        WeatherClient.Forecast forecast = WeatherClient.parseForecast(SAMPLE, LOCATION, 3);
        assertNotNull(forecast);
        assertEquals("广州市", forecast.location().city());
        assertEquals("2026-11-14", forecast.updateDate());
        assertEquals("11:48", forecast.updateTime());

        WeatherClient.Now now = forecast.now();
        assertEquals(23.2, now.temp(), 1e-9);
        assertEquals("多云", now.weather());
        assertEquals(26, now.high());
        assertEquals(20, now.low());
        assertEquals("93%", now.humidity());
        assertEquals("优", now.quality());
        assertEquals(6.0, now.pm25(), 1e-9);
        assertEquals("北风", now.wind());
        assertEquals("2级", now.windLevel());

        List<WeatherClient.Day> days = forecast.days();
        assertEquals(3, days.size());
        assertEquals("2026-11-16", days.get(2).date());
        assertEquals("小雨", days.get(2).weather());
        assertEquals(27, days.get(2).high());
        assertEquals(47, days.get(2).aqi());
    }

    @Test
    void parseForecastRejectsFailedStatus() {
        assertNull(WeatherClient.parseForecast("{\"status\":404}", LOCATION, 7));
    }

    @Test
    void summaryMentionsRainAndTemperature() {
        WeatherClient.Forecast forecast = WeatherClient.parseForecast(SAMPLE, LOCATION, 3);
        assertNotNull(forecast);
        String summary = WeatherClient.summarize(forecast);
        assertTrue(summary.contains("广州市"));
        assertTrue(summary.contains("20~26℃"));
        assertTrue(summary.contains("下雨"));
        assertTrue(summary.contains("空气质量优"));
    }

    @Test
    void trimZeroKeepsDecimals() {
        assertEquals("23", WeatherClient.trimZero(23.0));
        assertEquals("23.2", WeatherClient.trimZero(23.2));
    }

    private static final WeatherClient.Location LOCATION =
            new WeatherClient.Location("101280101", "广州", "广东");

    private static final String SAMPLE = """
            {"message":"success","status":200,"date":"20261114","time":"2026-11-14 14:36:09",
             "cityInfo":{"city":"广州市","citykey":"101280101","parent":"广东","updateTime":"11:48"},
             "data":{"shidu":"93%","pm25":6.0,"pm10":10.0,"quality":"优","wendu":"23.2",
               "ganmao":"各类人群可自由活动",
               "forecast":[
                 {"date":"14","high":"高温 26℃","low":"低温 20℃","ymd":"2026-11-14","week":"星期六","sunrise":"06:39","sunset":"17:42","aqi":35,"fx":"北风","fl":"2级","type":"多云","notice":"阴晴之间"},
                 {"date":"15","high":"高温 28℃","low":"低温 21℃","ymd":"2026-11-15","week":"星期日","sunrise":"06:40","sunset":"17:42","aqi":52,"fx":"东北风","fl":"1级","type":"多云","notice":"阴晴之间"},
                 {"date":"16","high":"高温 27℃","low":"低温 22℃","ymd":"2026-11-16","week":"星期一","sunrise":"06:40","sunset":"17:42","aqi":47,"fx":"东南风","fl":"2级","type":"小雨","notice":"雨虽小"}
               ]}}
            """;
}
