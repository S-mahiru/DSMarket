package com.dsmarket.modules.ai.sse;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终答复切分成 {@code delta} 事件的纯逻辑切分器。
 *
 * <p>说明（诚实口径）：M1a 的上游 {@code DeepSeekChatClient} 为非流式，正文在整段生成后
 * 才返回；本切分器把整段正文按句读切成若干 {@code delta} 逐段下发，前端仍可做打字机展示。
 * 真·上游逐 token 流式属 F4 Provider 增量（换实现只改转译器，事件协议不变）。</p>
 *
 * <p>切分边界优先停在新行/句读，避免把 emoji（代理对）拦腰截断；保证分段拼接 === 原文。</p>
 */
public final class DeltaChunker {

    /** 单段上限（可调；纯展示粒度，不影响语义） */
    public static final int MAX_CHUNK = 48;

    private DeltaChunker() {
    }

    public static List<String> chunk(String text) {
        return chunk(text, MAX_CHUNK);
    }

    public static List<String> chunk(String text, int maxChunk) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int cut = Math.min(len, start + Math.max(1, maxChunk));
            // 1) 优先停在新行（自然段边界）
            for (int i = cut - 1; i > start; i--) {
                char c = text.charAt(i);
                if (c == '\n' || c == '\r') {
                    cut = i + 1;
                    break;
                }
            }
            // 2) 无换行时，尽量就近断在句读，避免一长句被硬切得很难看
            if (cut >= len || !(text.charAt(cut - 1) == '\n' || text.charAt(cut - 1) == '\r')) {
                int from = Math.max(start, cut - 12);
                for (int i = cut - 1; i >= from; i--) {
                    if (isBreak(text.charAt(i))) {
                        cut = i + 1;
                        break;
                    }
                }
            }
            // 3) 避免把代理对（emoji）劈开
            if (cut > start && cut < len) {
                char hi = text.charAt(cut - 1);
                char lo = text.charAt(cut);
                if (Character.isHighSurrogate(hi) && Character.isLowSurrogate(lo)) {
                    cut--;
                }
            }
            if (cut <= start) {
                cut = Math.min(len, start + Math.max(1, maxChunk)); // 兜底硬切
            }
            out.add(text.substring(start, cut));
            start = cut;
        }
        return out;
    }

    private static boolean isBreak(char c) {
        return c == '\n' || c == '\r' || c == '\t'
                || c == '。' || c == '！' || c == '？' || c == '；' || c == '，' || c == '、'
                || c == '.' || c == '!' || c == '?' || c == ';' || c == ','
                || c == ' ' || c == '　';
    }
}
