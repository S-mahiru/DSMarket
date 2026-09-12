package com.dsmarket.modules.ai.session;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 未解决信号计数的内存替身（C4 §4.6 F6 触发③）。
 *
 * <p>三个 AI 侧单测都要用它，故抽成共享替身而不是各写一份：{@code AiChatStreamServiceTest}
 * 要驱动"达阈值/未达阈值/恰好等于阈值"，{@code ChatHumanRouterImplTest} 要断言转人工时清零，
 * {@code AiIssueServiceImplTest} 不涉及（它在 service 外）。手写替身对齐项目约定
 * （只 mock 接口，不用 Mockito class-mocking）。</p>
 */
public class InMemoryUnresolvedSignalCounter implements UnresolvedSignalCounter {

    private final Map<Long, AtomicInteger> counts = new HashMap<>();
    /** clear 调用次数：供"转人工必须清零"这类断言 */
    public final AtomicInteger clearCalls = new AtomicInteger();

    @Override
    public int increment(Long userId) {
        return counts.computeIfAbsent(userId, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public int count(Long userId) {
        AtomicInteger n = counts.get(userId);
        return n == null ? 0 : n.get();
    }

    @Override
    public void clear(Long userId) {
        clearCalls.incrementAndGet();
        counts.remove(userId);
    }

    /** 直接把计数摆到某个值，省得测试里写一串 increment（模拟"此前已攒够 N 次"） */
    public void preset(Long userId, int n) {
        counts.put(userId, new AtomicInteger(n));
    }
}
