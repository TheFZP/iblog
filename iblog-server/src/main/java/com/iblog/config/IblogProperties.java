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

    private Predict predict = new Predict();

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

    /**
     * 全市场「次日看涨」预测。交易时段结束后 15:30 扫描，全部参数可在
     * application.yml 的 iblog.predict.* 覆盖。
     */
    @Data
    public static class Predict {
        /** 总开关；默认关，先 dry-run 验证数据合理再打开 */
        private boolean enabled = false;
        /** 预筛后进入 K 线打分的股票数（按成交额排序） */
        private int prefilterTopN = 300;
        /** 最终写入榜单的股票数 */
        private int outputTopK = 20;
        /** 进入新闻扫描的股票数（榜单候选中取前 M） */
        private int newsTopM = 60;
        /** 每日 K 线补拉请求预算 */
        private int klineFetchBudget = 300;
        /** K 线补拉速率（请求/秒） */
        private double klineFetchRatePerSec = 3;
        /** 预筛：最小成交额（万元） */
        private double minAmountWan = 5000;
        /** 预筛：最低价 */
        private double minPrice = 2;
        /** 预筛：最高价 */
        private double maxPrice = 500;
        /** 预筛：当日最大涨幅（%） */
        private double maxChangePct = 9.5;
        /** 预筛：排除 ST / 退 / N / C */
        private boolean excludeSt = true;
        /** 预筛：排除北交所 */
        private boolean excludeBse = true;
        /** 预筛：排除收盘涨停（次日难以按开盘价买入） */
        private boolean excludeLimitUpAtClose = true;
        /** 打分最少日 K 根数 */
        private int minBars = 60;
        /** 新闻原文保留天数 */
        private int newsKeepDays = 365;
        /** 概率校准回看交易日数 */
        private int calibrationLookbackDays = 60;
        /** 校准最少样本数，低于此值仅用启发式概率 */
        private int calibrationMinSamples = 30;
        /** 贝叶斯平滑强度 k */
        private int calibrationShrinkK = 30;
        /** 逐只资金流补充的前 N 只；v1 默认 0（不做） */
        private int flowEnrichTopN = 0;
        /** 是否允许手工触发 POST /api/stock/predict/refresh */
        private boolean manualTriggerEnabled = false;
    }
}
