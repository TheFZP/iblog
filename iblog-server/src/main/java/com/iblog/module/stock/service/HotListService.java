package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.HotStockItem;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 同花顺热榜数据源。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotListService {

    private final StockHttpClient http;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL = "https://dq.10jqka.com.cn/fuyao/hot_list_data/out/hot_list/v1/stock"
            + "?stock_type=a&type=hour&list_type=normal";

    private static final String REFERER = "https://www.10jqka.com.cn/";

    /**
     * 拉取热榜。
     */
    public List<HotStockItem> fetchHotList() {
        String text = http.getText(URL, REFERER, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception e) {
            throw new StockDataException("热榜数据解析失败（接口可能反爬拦截）");
        }

        JsonNode list = root.path("data").path("stock_list");
        if (!list.isArray()) {
            throw new StockDataException("热榜数据格式异常：缺少 data.stock_list");
        }

        List<HotStockItem> items = new ArrayList<>();
        for (JsonNode n : list) {
            HotStockItem item = new HotStockItem();
            item.setCode(n.path("code").asText(""));
            item.setName(n.path("name").asText(""));
            item.setRate(n.path("rate").asText("0"));
            item.setRiseAndFall(n.path("rise_and_fall").asDouble(0));
            item.setHotRankChg(n.path("hot_rank_chg").asLong(0));
            item.setOrder(n.path("order").asLong(0));
            item.setMarket(n.path("market").asLong(0));
            item.setPopularityTag(n.path("tag").path("popularity_tag").asText(""));

            List<String> tags = new ArrayList<>();
            JsonNode conceptTag = n.path("tag").path("concept_tag");
            if (conceptTag.isArray()) {
                conceptTag.forEach(t -> {
                    if (t.isTextual() && !t.asText().isBlank()) {
                        tags.add(t.asText());
                    }
                });
            }
            item.setTags(tags);
            items.add(item);
        }
        return items;
    }
}
