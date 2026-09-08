package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FAQ 兜底解析（主 REQ §2.3）：把用户问句在 faq 精选条目里 BM25 命中顶命，
 * 原文直发「（自动回复）+answer」，不经 LLM。
 *
 * <p>替换固定话术的兜底位置：AI 态 SSE 的低置信短路（search_knowledge NOT_COVERED）与
 * 上游故障 fallback 共用（C1 T5 / T7）。纯 BM25、<b>不依赖 embedding</b>——模型/embedding
 * 上游全挂时仍能答精选 FAQ（DB 直读）。顶命缺失/问句空/异常 → 固定 NO_HIT 话术，永不抛错。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFaqFallback {

    /** 无精选 FAQ 命中 / 空问句 / 检索异常时的兜底话术（后缀告知用户可重试或转人工）。 */
    public static final String NO_HIT_TEXT = "（自动回复）客服正忙，请稍后再试。如问题紧急可稍后重发或转人工客服。";

    private static final int TOP = 1;
    private static final int MAX_QUERY_LEN = 200;

    private final AiKnowledgeMapper knowledgeMapper;

    /**
     * 解析一次兜底内容：BM25 命中 published+faq 顶命 → 「（自动回复）+answer」；否则 NO_HIT_TEXT。
     *
     * @param userContent 用户本轮问句（内部 trim、截 ≤200）
     * @return 兜底正文（恒非 null、非空）
     */
    public String resolve(String userContent) {
        String q = userContent == null ? "" : userContent.trim();
        if (q.isEmpty()) {
            return NO_HIT_TEXT;
        }
        if (q.length() > MAX_QUERY_LEN) {
            q = q.substring(0, MAX_QUERY_LEN);
        }
        try {
            List<AiKnowledge> hits = knowledgeMapper.searchFaqTop(q, TOP);
            if (hits == null || hits.isEmpty()) {
                return NO_HIT_TEXT;
            }
            String answer = hits.get(0).getAnswer();
            if (answer == null || answer.isBlank()) {
                return NO_HIT_TEXT;
            }
            return "（自动回复）" + answer;
        } catch (RuntimeException e) {
            // 兜底自身不得再抛（否则本要兜底的故障会升级成 error）；退化固定话术
            log.warn("[ai] FAQ 兜底检索异常，退固定话术. q={}", q, e);
            return NO_HIT_TEXT;
        }
    }
}
