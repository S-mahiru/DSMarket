package com.dsmarket.modules.ai.limit;

/**
 * AI /chat 限流（REQ C1 E2/§9：每用户每分钟 20 次，超出 → 开流前 HTTP 429）。
 */
public interface ChatRateLimiter {

    /** 是否放行本次请求 */
    boolean allow(Long userId);
}
