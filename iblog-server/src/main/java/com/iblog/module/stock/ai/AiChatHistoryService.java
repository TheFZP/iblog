package com.iblog.module.stock.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.iblog.module.stock.entity.StockAiChatMessage;
import com.iblog.module.stock.entity.StockAiChatSession;
import com.iblog.module.stock.mapper.StockAiChatMessageMapper;
import com.iblog.module.stock.mapper.StockAiChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 对话历史的读写。
 *
 * <p>为什么落库：之前对话只存在 {@code AiPanel.vue} 的 {@code ref} 里，刷新页面、
 * 换个设备就丢。这里按「用户 + 股票」建一条会话，用户明天再打开还是能看到之前的分析结论。</p>
 *
 * <p>全局 AI 助手（不绑定个股）用空串 {@code stockCode} 归档在同一张表，不另设类型字段。</p>
 *
 * <p><b>为什么每个会话保留上限而不是归档全部</b>：对话是流水，越老越没用；
 * 保留最近 200 条既够回溯，又不会让单次加载把抽屉拖慢。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatHistoryService {

    /** 每个会话保留的消息条数上限。 */
    private static final int KEEP_MESSAGES = 200;
    /** 一次回给前端的消息条数上限（与前端 {@code buildHistory} 的 20 条上下文无关，这里只影响展示）。 */
    private static final int LOAD_LIMIT = 60;
    /** 单条正文落库上限，防异常输出把 MEDIUMTEXT 撑爆。 */
    private static final int CONTENT_MAX = 200_000;
    /** 思考过程落库上限；展示时本来就是折叠的，没必要存全量。 */
    private static final int REASONING_MAX = 20_000;

    private final StockAiChatSessionMapper sessionMapper;
    private final StockAiChatMessageMapper messageMapper;

    /**
     * 取（或新建）会话 id。
     *
     * <p>并发下两个标签页可能同时首次提问，唯一键冲突时回查一次即可，
     * 不为此引入分布式锁 —— 冲突是瞬时的，代价只是多一次 SELECT。</p>
     */
    public Long touchSession(Long userId, String stockCode, String stockName) {
        if (userId == null) {
            return null;
        }
        String code = normalizeCode(stockCode);
        StockAiChatSession session = findSession(userId, code);
        if (session == null) {
            session = new StockAiChatSession();
            session.setUserId(userId);
            session.setStockCode(code);
            session.setStockName(blankToEmpty(stockName));
            try {
                sessionMapper.insert(session);
                return session.getId();
            } catch (DuplicateKeyException e) {
                session = findSession(userId, code);
                if (session == null) {
                    log.warn("AI 会话创建冲突后仍未查到 userId={} code={}", userId, code);
                    return null;
                }
            }
        }
        String name = blankToEmpty(stockName);
        if (!name.isEmpty() && !name.equals(session.getStockName())) {
            session.setStockName(name);
            sessionMapper.updateById(session);
        }
        return session.getId();
    }

    /** 保存本轮用户提问；空白内容不写。 */
    public void saveUser(Long sessionId, String content) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        insert(sessionId, "user", truncate(content, CONTENT_MAX), null, null, null);
    }

    /**
     * 保存本轮 AI 回答。
     *
     * <p>正文、思考过程、选股卡片全空时（模型一个字都没吐出来）不写，
     * 避免历史里多一条空白回答。</p>
     */
    @Transactional
    public void saveAssistant(Long sessionId, String content, String reasoning,
                              String toolsJson, String picksJson) {
        if (sessionId == null) {
            return;
        }
        String text = blankToEmpty(content);
        String think = blankToEmpty(reasoning);
        if (text.isBlank() && think.isBlank() && isBlankJsonArray(picksJson)) {
            return;
        }
        insert(sessionId, "assistant", truncate(text, CONTENT_MAX),
                truncate(think, REASONING_MAX), toolsJson, picksJson);
        trim(sessionId);
    }

    /** 读取某只股票（或全局）的最近消息，按时间正序；没有会话时返回空。 */
    public History load(Long userId, String stockCode) {
        String code = normalizeCode(stockCode);
        if (userId == null) {
            return History.empty(code);
        }
        StockAiChatSession session = findSession(userId, code);
        if (session == null) {
            return History.empty(code);
        }
        List<StockAiChatMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<StockAiChatMessage>()
                .eq(StockAiChatMessage::getSessionId, session.getId())
                .orderByDesc(StockAiChatMessage::getId)
                .last("LIMIT " + LOAD_LIMIT));
        Collections.reverse(rows);
        List<HistoryMessage> messages = new ArrayList<>(rows.size());
        for (StockAiChatMessage row : rows) {
            messages.add(new HistoryMessage(row.getRole(), blankToEmpty(row.getContent()),
                    blankToEmpty(row.getReasoning()), parseArray(row.getTools()), parseArray(row.getPicks())));
        }
        return new History(session.getStockCode(), blankToEmpty(session.getStockName()), messages);
    }

    /** 清空某只股票（或全局）的对话；没有会话时静默成功。 */
    @Transactional
    public void clear(Long userId, String stockCode) {
        if (userId == null) {
            return;
        }
        StockAiChatSession session = findSession(userId, normalizeCode(stockCode));
        if (session == null) {
            return;
        }
        messageMapper.delete(new LambdaQueryWrapper<StockAiChatMessage>()
                .eq(StockAiChatMessage::getSessionId, session.getId()));
        sessionMapper.deleteById(session.getId());
    }

    private void insert(Long sessionId, String role, String content,
                        String reasoning, String toolsJson, String picksJson) {
        StockAiChatMessage row = new StockAiChatMessage();
        row.setSessionId(sessionId);
        row.setRole(role);
        row.setContent(content);
        row.setReasoning(reasoning);
        row.setTools(toolsJson);
        row.setPicks(picksJson);
        messageMapper.insert(row);
    }

    /** 只保留最近 {@link #KEEP_MESSAGES} 条；多查一条用于判断是否超限。 */
    private void trim(Long sessionId) {
        List<StockAiChatMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<StockAiChatMessage>()
                .select(StockAiChatMessage::getId)
                .eq(StockAiChatMessage::getSessionId, sessionId)
                .orderByDesc(StockAiChatMessage::getId)
                .last("LIMIT " + (KEEP_MESSAGES + 1)));
        if (rows.size() <= KEEP_MESSAGES) {
            return;
        }
        List<Long> drop = rows.subList(KEEP_MESSAGES, rows.size()).stream()
                .map(StockAiChatMessage::getId)
                .toList();
        messageMapper.deleteByIds(drop);
    }

    private StockAiChatSession findSession(Long userId, String code) {
        List<StockAiChatSession> found = sessionMapper.selectList(new LambdaQueryWrapper<StockAiChatSession>()
                .eq(StockAiChatSession::getUserId, userId)
                .eq(StockAiChatSession::getStockCode, code)
                .last("LIMIT 1"));
        return found.isEmpty() ? null : found.get(0);
    }

    /** 存的是 JSON 文本；历史数据损坏也不该让整个面板打不开，退化成空数组。 */
    private static JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) {
            return AiJson.MAPPER.createArrayNode();
        }
        try {
            JsonNode node = AiJson.MAPPER.readTree(json);
            return node != null && node.isArray() ? node : AiJson.MAPPER.createArrayNode();
        } catch (Exception e) {
            return AiJson.MAPPER.createArrayNode();
        }
    }

    private static boolean isBlankJsonArray(String json) {
        return json == null || json.isBlank() || "[]".equals(json.trim());
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max) + "…[已截断]";
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 历史响应。
     *
     * <p>{@code stockCode} 回传是为了让前端能识别「这不是我刚请求的那只」，
     * 切股票时旧请求晚到可以据此丢弃。</p>
     */
    public record History(String stockCode, String stockName, List<HistoryMessage> messages) {
        static History empty(String code) {
            return new History(code, "", List.of());
        }
    }

    /** 单条历史消息；{@code tools} / {@code picks} 永远是数组，前端不用判空。 */
    public record HistoryMessage(String role, String content, String reasoning,
                                 JsonNode tools, JsonNode picks) {
    }
}
