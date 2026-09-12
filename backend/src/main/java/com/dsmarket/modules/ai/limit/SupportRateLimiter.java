package com.dsmarket.modules.ai.limit;

/**
 * 人工态发消息限流（REQ C4 §4.4：10 次/分/用户，超出 → HTTP 429 SUPPORT_RATE_LIMITED，
 * 消息不落库）。
 *
 * <p>与 {@link ChatRateLimiter}（/chat 的 20 次/分）<b>计数键独立</b>：两条通道限流互不占用额度，
 * 否则买家在 AI 态刷满次数就没法转人工了。</p>
 */
public interface SupportRateLimiter {

    /** 是否放行本次发消息 */
    boolean allow(Long userId);
}
