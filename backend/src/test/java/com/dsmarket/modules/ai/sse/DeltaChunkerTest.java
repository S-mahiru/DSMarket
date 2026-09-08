package com.dsmarket.modules.ai.sse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeltaChunker 纯逻辑切分：拼接不变性 + 单段上限 + 句读/换行优先断点。
 */
class DeltaChunkerTest {

    @Test
    void emptyOrNull_noChunks() {
        assertTrue(DeltaChunker.chunk("").isEmpty());
        assertTrue(DeltaChunker.chunk(null).isEmpty());
    }

    @Test
    void punctuationPreferredBreak() {
        assertEquals(List.of("A。", "B。", "C。"), DeltaChunker.chunk("A。B。C。", 3));
    }

    @Test
    void asciiHardCutWhenNoBoundary() {
        assertEquals(List.of("abc", "def", "gh"), DeltaChunker.chunk("abcdefgh", 3));
    }

    @Test
    void newlineIsPreferredBoundary() {
        // 单段上限很大，但应断在换行处而不是吞掉整段
        assertEquals(List.of("第一行\n", "第二行"), DeltaChunker.chunk("第一行\n第二行", 100));
    }

    @Test
    void concatenationPreservesOriginal_andRespectsMax() {
        String text = "你好，黑海商城的 AI 客服助手。请帮我查一下最近一笔订单的状态好吗？"
                + "我想知道订单号、金额和物流进展，谢谢！😀我们继续。";
        List<String> chunks = DeltaChunker.chunk(text, 12);
        assertTrue(chunks.size() >= 2, "长文应切成多段");
        assertEquals(text, String.join("", chunks), "切分必须无损可拼接");
        for (String c : chunks) {
            assertTrue(!c.isEmpty() && c.length() <= 12, "每段 1~max，实际=[" + c + "]");
        }
    }
}
