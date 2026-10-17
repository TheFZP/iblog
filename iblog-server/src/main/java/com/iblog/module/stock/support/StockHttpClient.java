package com.iblog.module.stock.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 行情数据源的 HTTP 客户端。
 *
 * <p>为什么不用 RestTemplate：腾讯行情接口返回 <b>GBK</b> 编码且无 charset 头，
 * 需要拿原始字节自己解码，用 Java 11+ 的 {@link HttpClient} 最直接。</p>
 *
 * <p>客户端单例复用（{@link HttpClient} 内部有连接池，每次 new 会泄漏连接）。</p>
 */
@Slf4j
@Component
public class StockHttpClient {

    /** 腾讯行情接口是 GBK，且响应头不带 charset，必须显式按 GBK 解码。 */
    public static final Charset GBK = Charset.forName("GB18030");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    /** 腾讯系列接口的 Referer，缺了会被拒或返回空。 */
    public static final String REFERER_QT = "https://qt.gtimg.cn/";
    public static final String REFERER_IFZQ = "https://web.ifzq.gtimg.cn/";
    public static final String REFERER_SMARTBOX = "https://smartbox.gtimg.cn/";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 行情接口会做 302 跳转，跟随但不跨主机（防 SSRF）
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * GET 文本，按指定字符集解码。
     *
     * @param url      完整 URL
     * @param referer  Referer 头，可为空
     * @param charset  解码字符集（腾讯 GBK / 东财 UTF-8）
     */
    public String getText(String url, String referer, Charset charset) {
        byte[] bytes = getBytes(url, referer);
        return new String(bytes, charset == null ? StandardCharsets.UTF_8 : charset);
    }

    /**
     * GET 原始字节，调用方自行解码（GBK 场景用这个）。
     */
    public byte[] getBytes(String url, String referer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept", "*/*")
                .GET();
        if (referer != null && !referer.isEmpty()) {
            builder.header("Referer", referer);
        }

        try {
            HttpResponse<byte[]> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new StockDataException("上游返回 HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (StockDataException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StockDataException("请求被中断");
        } catch (Exception e) {
            log.warn("行情请求失败 url={} err={}", url, e.toString());
            throw new StockDataException("行情数据获取失败：" + e.getMessage());
        }
    }

    /**
     * 行情数据异常。继承 RuntimeException，由 GlobalExceptionHandler 统一转成 R.fail。
     */
    public static class StockDataException extends RuntimeException {
        public StockDataException(String message) {
            super(message);
        }
    }
}
