package com.iblog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 项目自定义配置，对应 application.yml 中的 iblog.* 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "iblog")
public class IblogProperties {

    private Jwt jwt = new Jwt();

    private Auth auth = new Auth();

    private Paper paper = new Paper();

    @Data
    public static class Jwt {
        /** 签名密钥，长度需 >= 32 字符 */
        private String secret;
        /** 过期时间（秒），默认 1 天 */
        private long expire = 86400L;
        /** 令牌请求头名称 */
        private String header = "Authorization";
        /** 令牌前缀 */
        private String prefix = "Bearer ";
    }

    /**
     * 官网行情登录态。后台登录后除返回 header 令牌外，还会下发一个 HttpOnly Cookie，
     * 供官网（同源 / 同主域的不同地址）识别「是否登录过后台」。
     */
    @Data
    public static class Auth {
        /** Cookie 名称 */
        private String cookieName = "iblog_auth";
        /** 是否仅 HTTPS 发送；线上必须 true，本地 http 开发用 false */
        private boolean cookieSecure = false;
        /** Cookie 作用域；留空表示仅当前主机（不同端口会自动共享） */
        private String cookieDomain = "";
        /** 跨域一次性登录票据有效期（秒） */
        private long ticketExpire = 120L;
    }

    /**
     * 模拟盘参数。全部可在 application.yml 的 iblog.paper.* 覆盖，
     * 线上通过环境变量调整（见 deploy/.env.example）。
     */
    @Data
    public static class Paper {
        /** 自动交易引擎总开关；默认关，验证后再开 */
        private boolean enabled = false;
        /** 开户初始资金 */
        private BigDecimal initialCash = new BigDecimal("1000000");
        /** 引擎 tick 间隔（毫秒） */
        private long tickMs = 15000L;
        /** 选股结果刷新间隔（毫秒） */
        private long selectionRefreshMs = 300000L;
        /** 佣金费率，如 0.00025 = 万 2.5 */
        private double commissionRate = 0.00025;
        /** 最低佣金（元） */
        private BigDecimal commissionMin = new BigDecimal("5");
        /** 印花税（仅卖出），0.0005 = 万 5 */
        private double stampTaxRate = 0.0005;
        /** 过户费，0.00001 = 十万分之一 */
        private double transferFeeRate = 0.00001;
        /** 市价单滑点（基点，1bp = 0.01%） */
        private int slippageBps = 5;
        /** 单票最大仓位占总资产比例 */
        private double maxPositionPct = 0.2;
        /** 总仓位上限 */
        private double maxTotalPositionPct = 0.8;
        /** 单日亏损熔断：当日亏损超过该比例则只卖不买 */
        private double maxDailyLossPct = 0.03;
        /** 每日最多委托笔数（含自动交易） */
        private int maxOrdersPerDay = 20;
        /** 每日最多买入候选数 */
        private int maxCandidates = 5;
        /** 节假日（逗号分隔 yyyy-MM-dd），每年更新；留空只按周末判断 */
        private String holidayDates = "";
        /** 本地调试：非交易时段也允许下单/运行引擎 */
        private boolean forceTradingHours = false;
    }
}
