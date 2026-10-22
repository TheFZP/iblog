package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.exception.BizException;
import com.iblog.module.paper.dto.PaperRuleSaveRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperRule;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperRuleMapper;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.vo.PaperRuleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则模板库。
 *
 * <p>paper_strategy 只保存账户「当前生效」的一套参数，本服务负责用户级的多套命名规则：
 * 页面可以把当前参数另存为模板，也可以把模板一键复制到任意账户（复制后仍可单独微调，
 * 二者不互相绑定）。参数一律走 {@link PaperStrategyParams#parse} 规范化，
 * 与手动保存策略的钳制规则保持一致。</p>
 */
@Service
@RequiredArgsConstructor
public class PaperRuleService {

    /** 每用户最多保存的模板数，防止无限堆积。 */
    private static final int MAX_RULES = 20;
    /** 首次进入时自动落一套基础规则，保证下拉框不为空；名字要能看出规则取向。 */
    private static final String DEFAULT_RULE_NAME = "趋势跟随·均衡版";
    private static final int MAX_NAME_LENGTH = 32;

    private final PaperRuleMapper ruleMapper;
    private final PaperStrategyService strategyService;
    private final PaperAccountService accountService;
    private final ObjectMapper objectMapper;

    /** 模板列表；传 accountId 时顺便标记哪一套与账户当前生效参数一致。 */
    public List<PaperRuleVO> list(Long userId, Long accountId) {
        List<PaperRule> rules = ensureSeed(userId);
        String currentJson = null;
        if (accountId != null) {
            PaperStrategy strategy = strategyService.ensureDefault(accountId);
            currentJson = canonical(strategy.getParams());
        }
        List<PaperRuleVO> result = new ArrayList<>(rules.size());
        for (PaperRule rule : rules) {
            String ruleJson = canonical(rule.getParams());
            result.add(toVO(rule, currentJson != null && currentJson.equals(ruleJson)));
        }
        return result;
    }

    @Transactional
    public PaperRuleVO create(Long userId, PaperRuleSaveRequest request) {
        String name = normalizeName(request == null ? null : request.getName());
        String params = normalizeParams(request == null ? null : request.getParams());
        List<PaperRule> existing = listRaw(userId);
        if (existing.size() >= MAX_RULES) {
            throw new BizException(400, "规则模板最多保存 " + MAX_RULES + " 套");
        }
        boolean duplicate = existing.stream().anyMatch(rule -> name.equals(rule.getName()));
        if (duplicate) {
            throw new BizException(400, "已存在同名规则：" + name);
        }
        PaperRule rule = new PaperRule();
        rule.setUserId(userId);
        rule.setName(name);
        rule.setParams(params);
        ruleMapper.insert(rule);
        return toVO(rule, false);
    }

    @Transactional
    public PaperRuleVO update(Long userId, Long ruleId, PaperRuleSaveRequest request) {
        PaperRule rule = requireOwned(userId, ruleId);
        if (request != null && request.getName() != null) {
            String name = normalizeName(request.getName());
            boolean duplicate = listRaw(userId).stream()
                    .anyMatch(other -> !other.getId().equals(ruleId) && name.equals(other.getName()));
            if (duplicate) {
                throw new BizException(400, "已存在同名规则：" + name);
            }
            rule.setName(name);
        }
        if (request != null && request.getParams() != null) {
            rule.setParams(normalizeParams(request.getParams()));
        }
        ruleMapper.updateById(rule);
        return toVO(rule, false);
    }

    @Transactional
    public void delete(Long userId, Long ruleId) {
        PaperRule rule = requireOwned(userId, ruleId);
        ruleMapper.deleteById(rule.getId());
    }

    /** 把模板参数复制到账户当前策略；账户之后仍可单独微调。 */
    @Transactional
    public PaperStrategy apply(Long userId, Long accountId, Long ruleId) {
        PaperRule rule = requireOwned(userId, ruleId);
        PaperAccount account = accountService.require(userId, accountId);
        return strategyService.replaceParams(account.getId(), rule.getParams());
    }

    // ---------- 内部 ----------

    private List<PaperRule> ensureSeed(Long userId) {
        List<PaperRule> rules = listRaw(userId);
        if (!rules.isEmpty()) {
            return rules;
        }
        PaperRule rule = new PaperRule();
        rule.setUserId(userId);
        rule.setName(DEFAULT_RULE_NAME);
        rule.setParams(PaperStrategyParams.defaultJson(objectMapper));
        ruleMapper.insert(rule);
        return listRaw(userId);
    }

    private List<PaperRule> listRaw(Long userId) {
        return ruleMapper.selectList(Wrappers.<PaperRule>lambdaQuery()
                .eq(PaperRule::getUserId, userId)
                .orderByAsc(PaperRule::getId));
    }

    private PaperRule requireOwned(Long userId, Long ruleId) {
        PaperRule rule = ruleId == null ? null : ruleMapper.selectById(ruleId);
        if (rule == null || !userId.equals(rule.getUserId())) {
            throw new BizException(404, "规则模板不存在");
        }
        return rule;
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BizException(400, "规则名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BizException(400, "规则名称最多 " + MAX_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    private String normalizeParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return PaperStrategyParams.defaultJson(objectMapper);
        }
        return canonical(raw);
    }

    private String canonical(String json) {
        return PaperStrategyParams.parse(objectMapper, json).toJson(objectMapper);
    }

    private PaperRuleVO toVO(PaperRule rule, boolean active) {
        return PaperRuleVO.builder()
                .id(rule.getId())
                .name(rule.getName())
                .params(rule.getParams())
                .active(active)
                .createTime(rule.getCreateTime())
                .updateTime(rule.getUpdateTime())
                .build();
    }
}
