package com.iblog.module.stock.ai;

import java.util.List;

/**
 * 桌面版 17 个 AI 工具（function calling）的 schema 常量（逐字搬运自 stock-analysis-prh）。
 *
 * <p>每条对应桌面端 src/skills/*.js 里一个 {@code type: "function"} 定义：
 * name / description 原样搬运，parameters 序列化为紧凑 JSON，systemPrompt 复用
 * {@link AiToolPrompts} 中对应的 skill 提示词。
 */
public final class AiToolSchemas {

    private AiToolSchemas() {
    }

    /**
     * 一个工具的完整定义。
     *
     * @param name           工具名（原样）
     * @param description    工具描述（原样，中文不改）
     * @param parametersJson parameters 对象序列化后的紧凑 JSON
     * @param systemPrompt   该工具所属 skill 的系统提示词
     */
    public record ToolDef(String name, String description, String parametersJson, String systemPrompt) {
    }

    /** get_stock_quote 的 description（原样） */
    private static final String DESC_GET_STOCK_QUOTE = """
获取个股实时行情，包括价格、涨跌幅、成交量、成交额、换手率、市盈率等。输入股票代码如 600519。""";

    /** get_stock_quote 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_QUOTE = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519 或 300750"}},"required":["code"]}""";

    /** get_stock_quotes_batch 的 description（原样） */
    private static final String DESC_GET_STOCK_QUOTES_BATCH = """
一次获取多只股票的实时行情（最多 50 只；A 股批量请求、港股逐只）。对比多只股票、分析板块内个股强弱、批量评估候选标的时使用，比逐只调用 get_stock_quote 更省轮次。返回数组，每项含 code/name/price/changePct/turnover 等字段。""";

    /** get_stock_quotes_batch 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_QUOTES_BATCH = """
{"type":"object","properties":{"codes":{"type":"array","items":{"type":"string"},"description":"股票代码数组，如 [\\"600519\\", \\"300750\\"]，最多 50 只"}},"required":["codes"]}""";

    /** get_stock_kline 的 description（原样） */
    private static final String DESC_GET_STOCK_KLINE = """
获取个股 K 线数据，用于技术分析（均线、MACD、KDJ 等）。支持日K(day)、周K(week)、月K(month)、分钟K(m5/m15/m30/m60)。统一返回最近 60 根（前复权；分钟K 无复权、港股不支持分钟K）。""";

    /** get_stock_kline 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_KLINE = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519"},"period":{"type":"string","enum":["day","week","month","m5","m15","m30","m60"],"description":"K 线周期：day=日K, week=周K, month=月K, m5=5分钟, m15=15分钟, m30=30分钟, m60=60分钟"}},"required":["code","period"]}""";

    /** get_stock_money_flow 的 description（原样） */
    private static final String DESC_GET_STOCK_MONEY_FLOW = """
获取个股资金流向（主力/超大单/大单/中单/小单 各档净流入金额（万元）及占比），判断各层级资金动向。""";

    /** get_stock_money_flow 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_MONEY_FLOW = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519"}},"required":["code"]}""";

    /** get_stock_money_flow_history 的 description（原样） */
    private static final String DESC_GET_STOCK_MONEY_FLOW_HISTORY = """
获取个股近 N 个交易日的主力资金净流入历史（单位万元，按日期升序，默认 30 天）。分析资金持续流入/流出趋势、判断资金态度是否转变时使用；与当日快照 get_stock_money_flow 结合，可回答「这几天主力一直在流出吗」类问题。""";

    /** get_stock_money_flow_history 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_MONEY_FLOW_HISTORY = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519"},"limit":{"type":"number","description":"返回最近 N 个交易日，默认 30，最大 60","minimum":5,"maximum":60}},"required":["code"]}""";

    /** get_stock_industry 的 description（原样） */
    private static final String DESC_GET_STOCK_INDUSTRY = """
获取个股行业分析数据：所属行业名称、行业内营收排名、市场表现（相对沪深300超额收益）。""";

    /** get_stock_industry 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_INDUSTRY = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519"}},"required":["code"]}""";

    /** get_market_indices 的 description（原样） */
    private static final String DESC_GET_MARKET_INDICES = """
获取大盘主要指数实时行情（上证指数、深证成指、创业板指、沪深300、科创50、中证500）。""";

    /** get_market_indices 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_MARKET_INDICES = """
{"type":"object","properties":{}}""";

    /** get_stock_intraday 的 description（原样） */
    private static final String DESC_GET_STOCK_INTRADAY = """
获取个股当日分时数据，返回逐分钟的价格、均价、VWAP、成交量/额。用于判断当日走势形态（冲高回落、探底回升、单边拉升、横盘整理）、均价线支撑压力、日内买卖点参考。返回 items 为分钟序列，preClose 为昨收。""";

    /** get_stock_intraday 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_STOCK_INTRADAY = """
{"type":"object","properties":{"code":{"type":"string","description":"股票代码，如 600519 或 300750，港股如 00700"}},"required":["code"]}""";

    /** get_hot_list 的 description（原样） */
    private static final String DESC_GET_HOT_LIST = """
获取当日实时热榜（同花顺人气榜），返回热度排名靠前的股票，含热度值、涨跌幅、排名变化、概念标签。用于判断市场热点题材、人气龙头股、情绪方向。""";

    /** get_hot_list 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_HOT_LIST = """
{"type":"object","properties":{}}""";

    /** search_stocks 的 description（原样） */
    private static final String DESC_SEARCH_STOCKS = """
按名称或代码关键字搜索股票，返回匹配列表（code/name/market，market 为 SH/SZ/HK）。当用户用简称、模糊名称、或只有部分代码提到股票时，先用它确认准确代码，再调用行情等工具。""";

    /** search_stocks 的 parameters（紧凑 JSON） */
    private static final String PARAMS_SEARCH_STOCKS = """
{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键字，如「茅台」「平安」「600」「腾讯」"}},"required":["keyword"]}""";

    /** read_user_profile 的 description（原样） */
    private static final String DESC_READ_USER_PROFILE = """
读取用户的投资画像（Markdown 文本）：风险偏好、持仓偏好、投资风格、关注标的等。用户要求个性化分析（如「结合我的情况分析」「适不适合我」）时读取，让建议更贴合用户。""";

    /** read_user_profile 的 parameters（紧凑 JSON） */
    private static final String PARAMS_READ_USER_PROFILE = """
{"type":"object","properties":{}}""";

    /** save_user_profile 的 description（原样） */
    private static final String DESC_SAVE_USER_PROFILE = """
保存用户投资画像（Markdown 文本），会覆盖现有内容。仅当用户明确要求记录画像信息时使用，且必须在现有内容基础上追加而非重写，避免丢失用户已保存的信息。""";

    /** save_user_profile 的 parameters（紧凑 JSON） */
    private static final String PARAMS_SAVE_USER_PROFILE = """
{"type":"object","properties":{"content":{"type":"string","description":"完整的画像 Markdown 内容（需包含原有内容 + 新增内容）"}},"required":["content"]}""";

    /** get_fx_rate 的 description（原样） */
    private static final String DESC_GET_FX_RATE = """
获取港元兑人民币汇率（CNY per HKD），约 0.9 左右。分析港股价格、市值、金额换算为人民币时使用。""";

    /** get_fx_rate 的 parameters（紧凑 JSON） */
    private static final String PARAMS_GET_FX_RATE = """
{"type":"object","properties":{}}""";

    /** web_search 的 description（原样） */
    private static final String DESC_WEB_SEARCH = """
搜索财经新闻获取最新信息（东方财富新闻库，按相关性排序，每条带发布时间）。关键词规范（务必遵守）：用 2-4 个**实词**，格式「核心实体 + 限定维度」。正确示例：「贵州茅台 批价」「宁德时代 中报」「央行 降息 2026」。**严禁**使用「最新消息/最新新闻/怎么样/如何/情况/动态」等泛词——泛词会污染相关性排序导致返回无关新闻（实测搜「贵州茅台 最新消息」返回 ETF 新闻）。搜不到结果时先判断：关键词是否太冷门/太长？换个更通用的说法试试，最多搜 2 次，搜不到就直接说「未找到相关信息」。""";

    /** web_search 的 parameters（紧凑 JSON） */
    private static final String PARAMS_WEB_SEARCH = """
{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，必须用实词且不含泛词。如「宁德时代 中报」「央行 降息 2026」「贵州茅台 批价」"},"max_results":{"type":"number","description":"期望返回的结果数量，默认 10，最大 15"}},"required":["query"]}""";

    /** web_fetch 的 description（原样） */
    private static final String DESC_WEB_FETCH = """
获取指定网页的纯文本内容。当 web_search 返回了感兴趣的链接，或用已知 URL 需要读取具体内容时使用。适用于读取财经新闻全文、公司公告详情、研究报告等。""";

    /** web_fetch 的 parameters（紧凑 JSON） */
    private static final String PARAMS_WEB_FETCH = """
{"type":"object","properties":{"url":{"type":"string","description":"要抓取的网页完整 URL，如 https://example.com/article/123"}},"required":["url"]}""";

    /** stock_screener 的 description（原样） */
    private static final String DESC_STOCK_SCREENER = """
问财自然语言选股：按条件筛选全市场 A 股/北交所股票，返回匹配股票的代码、名称、现价及关键条件列（JSON）。当用户要求按财务/估值/技术/资金条件选股（市盈率、市值、ROE、涨幅、换手率、股息率等）时使用。只用于批量筛选，不用于单只股票详情（用 get_stock_quote 等）。""";

    /** stock_screener 的 parameters（紧凑 JSON） */
    private static final String PARAMS_STOCK_SCREENER = """
{"type":"object","properties":{"question":{"type":"string","description":"问财查询语句：中文条件、分号分隔，如「总市值小于300亿; 市盈率小于20; 连续3年净资产收益率大于15%」"}},"required":["question"]}""";

    /** render_stock_picks 的 description（原样） */
    private static final String DESC_RENDER_STOCK_PICKS = """
把选股/推荐结论渲染成卡片展示（代码/名称/现价/涨跌幅/理由），并提供「加入自选」「查看详情」操作。当用户要求选出/推荐股票并给出清单时，在最终回答前调用一次，传入精选的股票列表（通常 3-10 只）。代码必须是纯数字：A 股 6 位 / 北交所 6 位（43/82/83/87/88/92 开头）/ 港股 5 位，去掉 .SH/.SZ/.BJ 后缀。现价与涨跌幅必须来自 get_stock_quote 等工具的真实返回，没有数据就不要填。""";

    /** render_stock_picks 的 parameters（紧凑 JSON） */
    private static final String PARAMS_RENDER_STOCK_PICKS = """
{"type":"object","properties":{"picks":{"type":"array","description":"要渲染的股票卡片列表（建议 3-10 只）","items":{"type":"object","properties":{"code":{"type":"string","description":"股票代码（纯数字，去后缀）"},"name":{"type":"string","description":"股票名称"},"price":{"type":"number","description":"现价（来自行情工具，缺失可不填）"},"changePct":{"type":"number","description":"涨跌幅 %（来自行情工具，缺失可不填）"},"reason":{"type":"string","description":"入选/推荐理由（2-3 句，基于工具数据的真实判断）"}},"required":["code","name"]}}},"required":["picks"]}""";

    /** 全部 17 个工具定义（顺序：行情 → K 线 → 资金流 → 行业 → 大盘 → 分时 → 热榜 → 搜索 → 用户上下文 → 联网 → 选股 → 卡片） */
    public static final List<ToolDef> TOOLS = List.of(
            new ToolDef("get_stock_quote", DESC_GET_STOCK_QUOTE, PARAMS_GET_STOCK_QUOTE, AiToolPrompts.SKILL_PROMPT_STOCK_QUOTE),
            new ToolDef("get_stock_quotes_batch", DESC_GET_STOCK_QUOTES_BATCH, PARAMS_GET_STOCK_QUOTES_BATCH, AiToolPrompts.SKILL_PROMPT_STOCK_QUOTE),
            new ToolDef("get_stock_kline", DESC_GET_STOCK_KLINE, PARAMS_GET_STOCK_KLINE, AiToolPrompts.SKILL_PROMPT_KLINE_ANALYSIS),
            new ToolDef("get_stock_money_flow", DESC_GET_STOCK_MONEY_FLOW, PARAMS_GET_STOCK_MONEY_FLOW, AiToolPrompts.SKILL_PROMPT_MONEY_FLOW),
            new ToolDef("get_stock_money_flow_history", DESC_GET_STOCK_MONEY_FLOW_HISTORY, PARAMS_GET_STOCK_MONEY_FLOW_HISTORY, AiToolPrompts.SKILL_PROMPT_MONEY_FLOW),
            new ToolDef("get_stock_industry", DESC_GET_STOCK_INDUSTRY, PARAMS_GET_STOCK_INDUSTRY, AiToolPrompts.SKILL_PROMPT_INDUSTRY),
            new ToolDef("get_market_indices", DESC_GET_MARKET_INDICES, PARAMS_GET_MARKET_INDICES, AiToolPrompts.SKILL_PROMPT_MARKET_INDICES),
            new ToolDef("get_stock_intraday", DESC_GET_STOCK_INTRADAY, PARAMS_GET_STOCK_INTRADAY, AiToolPrompts.SKILL_PROMPT_INTRADAY),
            new ToolDef("get_hot_list", DESC_GET_HOT_LIST, PARAMS_GET_HOT_LIST, AiToolPrompts.SKILL_PROMPT_MARKET_OVERVIEW),
            new ToolDef("search_stocks", DESC_SEARCH_STOCKS, PARAMS_SEARCH_STOCKS, AiToolPrompts.SKILL_PROMPT_STOCK_SEARCH),
            new ToolDef("read_user_profile", DESC_READ_USER_PROFILE, PARAMS_READ_USER_PROFILE, AiToolPrompts.SKILL_PROMPT_USER_CONTEXT),
            new ToolDef("save_user_profile", DESC_SAVE_USER_PROFILE, PARAMS_SAVE_USER_PROFILE, AiToolPrompts.SKILL_PROMPT_USER_CONTEXT),
            new ToolDef("get_fx_rate", DESC_GET_FX_RATE, PARAMS_GET_FX_RATE, AiToolPrompts.SKILL_PROMPT_USER_CONTEXT),
            new ToolDef("web_search", DESC_WEB_SEARCH, PARAMS_WEB_SEARCH, AiToolPrompts.SKILL_PROMPT_WEB_SEARCH),
            new ToolDef("web_fetch", DESC_WEB_FETCH, PARAMS_WEB_FETCH, AiToolPrompts.SKILL_PROMPT_WEB_SEARCH),
            new ToolDef("stock_screener", DESC_STOCK_SCREENER, PARAMS_STOCK_SCREENER, AiToolPrompts.SKILL_PROMPT_IWENCAI_SELECT),
            new ToolDef("render_stock_picks", DESC_RENDER_STOCK_PICKS, PARAMS_RENDER_STOCK_PICKS, AiToolPrompts.SKILL_PROMPT_STOCK_PICKS)
    );

    /**
     * 按工具名查找工具定义，找不到返回 null。
     *
     * <p>模型返回的 function name 必须能对上本地定义，否则无法分发到对应处理器。
     */
    public static ToolDef findByName(String name) {
        if (name == null) {
            return null;
        }
        for (ToolDef def : TOOLS) {
            if (def.name().equals(name)) {
                return def;
            }
        }
        return null;
    }
}
