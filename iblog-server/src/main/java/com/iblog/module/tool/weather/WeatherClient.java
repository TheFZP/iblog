package com.iblog.module.tool.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 天气数据客户端（中国天气网口径）。
 *
 * <p>为什么不用 Open-Meteo：{@code api.open-meteo.com} 在阿里云出口实测 30 秒超时，
 * 而中国天气网的城市检索与 itboy 预报接口在生产机上都是 100ms 级返回。
 * 代价是 itboy 只提供 HTTP，因此这里只拉公开天气数据，不携带任何凭证。</p>
 *
 * <p>上游都不可控，所以解析逻辑拆成静态方法并单独单测：页面挂掉可以重发请求，
 * 解析写错则会安静地展示错误的温度。</p>
 */
@Slf4j
@Service
public class WeatherClient {

    /** 中国天气网城市检索（返回 JSONP 形式的数组）。 */
    static final String CITY_SEARCH = "http://toy1.weather.com.cn/search?cityname=";

    /** itboy 聚合的逐日预报。 */
    static final String FORECAST = "http://t.weather.itboy.net/api/weather/city/";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";

    private static final Pattern CITY_CODE = Pattern.compile("^\\d{9}$");
    private static final Pattern INT = Pattern.compile("-?\\d+");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 城市名 → 城市代码。查询很集中（通常几十个城市），缓存能省掉一半上游请求。 */
    private final Map<String, Location> cityCache = new ConcurrentHashMap<>();

    public record Location(String code, String city, String province) {
    }

    public record Now(double temp, String weather, int high, int low, String humidity,
                      Double pm25, String quality, int aqi, String wind, String windLevel,
                      String sunrise, String sunset) {
    }

    public record Day(String date, String week, int high, int low, String weather,
                      String wind, String windLevel, int aqi, String notice,
                      String sunrise, String sunset) {
    }

    public record Forecast(Location location, String updateDate, String updateTime,
                           Now now, List<Day> days) {
    }

    /**
     * 城市名 → 中国天气网城市代码。
     *
     * <p>用户输入「广州市」「广州」都接受；检索结果里混着景区（代码带字母后缀），
     * 只保留 9 位纯数字的城市代码，避免把「广州塔」当成城市。</p>
     */
    public Location resolveCity(String raw) {
        String city = raw == null ? "" : raw.trim();
        if (city.isEmpty()) {
            throw new BizException(400, "请输入城市名");
        }
        if (city.length() > 20) {
            throw new BizException(400, "城市名太长了");
        }
        Location cached = cityCache.get(city);
        if (cached != null) {
            return cached;
        }
        Location location = queryCity(city);
        if (location == null && city.endsWith("市")) {
            // 「广州市」检索不到时退回「广州」再试一次
            location = queryCity(city.substring(0, city.length() - 1));
        }
        if (location == null) {
            throw new BizException(404, "没找到「" + city + "」，换个城市名试试");
        }
        if (cityCache.size() > 500) {
            cityCache.clear();
        }
        cityCache.put(city, location);
        cityCache.put(location.city(), location);
        return location;
    }

    private Location queryCity(String city) {
        String url = CITY_SEARCH + URLEncoder.encode(city, StandardCharsets.UTF_8);
        String body;
        try {
            body = get(url);
        } catch (Exception e) {
            log.warn("城市检索失败 city={} err={}", city, e.toString());
            throw new BizException(502, "城市检索服务暂时不可用，稍后再试");
        }
        return parseCitySearch(body, city);
    }

    /** 解析 {@code ([{"ref":"101280101~guangdong~广州~..."}])}，失败返回 null。 */
    static Location parseCitySearch(String body, String wanted) {
        if (body == null) {
            return null;
        }
        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        List<Location> locations = new ArrayList<>();
        try {
            JsonNode array = new ObjectMapper().readTree(body.substring(start, end + 1));
            if (!array.isArray()) {
                return null;
            }
            for (JsonNode node : array) {
                String ref = node.path("ref").asText("");
                String[] parts = ref.split("~", -1);
                if (parts.length < 3 || !CITY_CODE.matcher(parts[0]).matches()) {
                    continue;
                }
                String name = parts[2].trim();
                if (name.isEmpty()) {
                    continue;
                }
                String province = parts.length > 9 ? parts[9].trim() : (parts.length > 1 ? parts[1].trim() : "");
                locations.add(new Location(parts[0], name, province));
            }
        } catch (Exception e) {
            return null;
        }
        if (locations.isEmpty()) {
            return null;
        }
        // 优先精确命中，避免「广州」先匹配到「广州南站」之类的分区
        String plain = wanted.endsWith("市") ? wanted.substring(0, wanted.length() - 1) : wanted;
        for (Location location : locations) {
            String name = location.city().endsWith("市")
                    ? location.city().substring(0, location.city().length() - 1) : location.city();
            if (name.equals(plain)) {
                return location;
            }
        }
        return locations.get(0);
    }

    /** 按城市名取预报；{@code days} 限制在 1~7。 */
    public Forecast forecast(String city, int days) {
        int limit = Math.max(1, Math.min(7, days));
        Location location = resolveCity(city);
        String body;
        try {
            body = get(FORECAST + location.code());
        } catch (Exception e) {
            log.warn("天气查询失败 city={} code={} err={}", city, location.code(), e.toString());
            throw new BizException(502, "天气数据暂时取不到，稍后再试");
        }
        Forecast forecast = parseForecast(body, location, limit);
        if (forecast == null) {
            throw new BizException(502, "天气数据格式异常，稍后再试");
        }
        return forecast;
    }

    static Forecast parseForecast(String body, Location location, int days) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            if (root.path("status").asInt() != 200) {
                return null;
            }
            JsonNode data = root.path("data");
            JsonNode list = data.path("forecast");
            if (!list.isArray() || list.isEmpty()) {
                return null;
            }
            List<Day> daily = new ArrayList<>();
            for (JsonNode item : list) {
                if (daily.size() >= days) {
                    break;
                }
                daily.add(new Day(
                        item.path("ymd").asText(""),
                        item.path("week").asText(""),
                        firstInt(item.path("high").asText("")),
                        firstInt(item.path("low").asText("")),
                        item.path("type").asText(""),
                        item.path("fx").asText(""),
                        item.path("fl").asText(""),
                        item.path("aqi").asInt(0),
                        item.path("notice").asText(""),
                        item.path("sunrise").asText(""),
                        item.path("sunset").asText("")));
            }
            JsonNode today = list.get(0);
            JsonNode cityInfo = root.path("cityInfo");
            Now now = new Now(
                    parseDouble(data.path("wendu").asText("")),
                    today.path("type").asText(""),
                    firstInt(today.path("high").asText("")),
                    firstInt(today.path("low").asText("")),
                    data.path("shidu").asText(""),
                    data.hasNonNull("pm25") ? data.path("pm25").asDouble() : null,
                    data.path("quality").asText(""),
                    today.path("aqi").asInt(0),
                    today.path("fx").asText(""),
                    today.path("fl").asText(""),
                    today.path("sunrise").asText(""),
                    today.path("sunset").asText(""));
            return new Forecast(
                    new Location(location.code(), cityInfo.path("city").asText(location.city()),
                            cityInfo.path("parent").asText(location.province())),
                    formatDate(root.path("date").asText("")),
                    cityInfo.path("updateTime").asText(""),
                    now, daily);
        } catch (Exception e) {
            return null;
        }
    }

    /** 规则摘要：AI 不可用时页面也要有一段能读的建议。 */
    public static String summarize(Forecast forecast) {
        if (forecast == null || forecast.now() == null || forecast.days().isEmpty()) {
            return "";
        }
        Now now = forecast.now();
        List<Day> days = forecast.days();
        int rainDays = 0;
        int snowDays = 0;
        for (Day day : days) {
            if (day.weather().contains("雪")) {
                snowDays++;
            } else if (day.weather().contains("雨") || day.weather().contains("雷")) {
                rainDays++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(forecast.location().city())
                .append("今天").append(now.weather().isEmpty() ? "天气未知" : now.weather())
                .append("，").append(now.low()).append("~").append(now.high()).append("℃，")
                .append("当前 ").append(trimZero(now.temp())).append("℃");
        if (!now.quality().isEmpty()) {
            sb.append("，空气质量").append(now.quality());
        }
        if (!now.wind().isEmpty()) {
            sb.append("，").append(now.wind());
            if (!now.windLevel().isEmpty()) {
                sb.append(now.windLevel());
            }
        }
        sb.append("。");
        if (rainDays > 0) {
            sb.append("未来 ").append(days.size()).append(" 天里有 ").append(rainDays).append(" 天可能下雨，随身带把伞。");
        } else if (snowDays > 0) {
            sb.append("有降雪，注意防滑保暖。");
        } else {
            sb.append("未来 ").append(days.size()).append(" 天以晴到多云为主，适合安排户外活动。");
        }
        int first = days.get(0).high();
        int last = days.get(days.size() - 1).high();
        if (last - first >= 4) {
            sb.append("气温逐步回升，但早晚仍凉。");
        } else if (first - last >= 4) {
            sb.append("后半段明显转凉，记得添衣。");
        }
        return sb.toString();
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", UA)
                .header("Referer", "http://www.weather.com.cn/")
                .header("Accept", "application/json,text/plain,*/*")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static int firstInt(String text) {
        Matcher matcher = INT.matcher(text == null ? "" : text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static double parseDouble(String text) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatDate(String raw) {
        try {
            return LocalDate.parse(raw, YMD).toString();
        } catch (Exception e) {
            return raw;
        }
    }

    static String trimZero(double value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
    }
}
