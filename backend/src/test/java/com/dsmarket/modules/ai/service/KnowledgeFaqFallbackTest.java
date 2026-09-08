package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeFaqFallback（主 REQ §2.3）：faq=true BM25 顶命原文直发、不经 LLM、
 * 不依赖 embedding；缺失/空问句/异常 → NO_HIT，永不抛错。
 */
class KnowledgeFaqFallbackTest {

    private static final int TOP = 1;

    private AiKnowledge faqRow(String answer) {
        AiKnowledge row = new AiKnowledge();
        row.setId(1L);
        row.setFaq(true);
        row.setAnswer(answer);
        return row;
    }

    @Test
    void nullOrBlankContent_noSearchReturnsNoHit() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);

        assertEquals(KnowledgeFaqFallback.NO_HIT_TEXT, fb.resolve(null));
        assertEquals(KnowledgeFaqFallback.NO_HIT_TEXT, fb.resolve("   "));
        verify(mapper, never()).searchFaqTop(eq("   "), eq(TOP));
    }

    @Test
    void topHit_returnsAutoReplyPrefixWithAnswer() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        when(mapper.searchFaqTop(eq("退款多久到账"), eq(TOP))).thenReturn(List.of(faqRow("退款1-3个工作日到账")));
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);

        assertEquals("（自动回复）退款1-3个工作日到账", fb.resolve("退款多久到账"));
    }

    @Test
    void noHit_returnsNoHitText() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        when(mapper.searchFaqTop(eq("查人工客服"), eq(TOP))).thenReturn(List.of());
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);

        assertEquals(KnowledgeFaqFallback.NO_HIT_TEXT, fb.resolve("查人工客服"));
    }

    @Test
    void hitWithBlankAnswer_returnsNoHitText() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        when(mapper.searchFaqTop(eq("空白答案"), eq(TOP))).thenReturn(List.of(faqRow("  ")));
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);

        assertEquals(KnowledgeFaqFallback.NO_HIT_TEXT, fb.resolve("空白答案"));
    }

    @Test
    void mapperThrows_degradesToNoHit_neverPropagates() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        when(mapper.searchFaqTop(eq("坏库"), eq(TOP))).thenThrow(new RuntimeException("db down"));
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);

        assertEquals(KnowledgeFaqFallback.NO_HIT_TEXT, fb.resolve("坏库"), "兜底自身不得抛，退固定话术");
    }

    @Test
    void overlongQuery_truncatedTo200BeforeSearch() {
        AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
        KnowledgeFaqFallback fb = new KnowledgeFaqFallback(mapper);
        String longQ = "问".repeat(210);

        fb.resolve(longQ);

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(mapper).searchFaqTop(cap.capture(), eq(TOP));
        String sent = cap.getValue();
        assertEquals(200, sent.length(), "检索前防御截 ≤200");
        assertEquals("问".repeat(200), sent);
    }
}
