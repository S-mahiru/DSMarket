package com.dsmarket.modules.ai.session;

/**
 * AI 单飞行门闩（REQ C1 E12）：同一 userId 只允许一处在途生成。
 *
 * <p>接口化便于编排层单测注入可控实现；生产实现走 Redis setIfAbsent（RedisAiSingleFlight）。</p>
 */
public interface AiSingleFlight {

    /**
     * 尝试获取该用户生成锁。
     *
     * @return true=本线程获得锁，可继续生成；false=已有在途生成，调用方应回 HTTP 409
     */
    boolean tryAcquire(Long userId);

    /** 释放锁（正常/异常路径均须调用）。 */
    void release(Long userId);
}
