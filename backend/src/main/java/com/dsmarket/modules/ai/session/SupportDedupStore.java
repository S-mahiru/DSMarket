package com.dsmarket.modules.ai.session;

/**
 * 人工态 clientMsgId 幂等（REQ C4 §4.4 P6 决议）：同 user+sessionId+clientMsgId 在
 * {@code ai.support.dedup-ttl}（默认 5s）内重复提交 → 忽略并返回 200，<b>不重复落库</b>
 * （防网络重试向坐席发重复消息）。
 *
 * <p>clientMsgId 缺省时不做内容去重 —— 由 10 次/分限流兜底（与 /chat 的语义一致）。</p>
 */
public interface SupportDedupStore {

    /**
     * 查该 clientMsgId 是否已在窗口内落过库。
     *
     * @return 命中 → 先前落库的 messageId；否则 null
     */
    Long findMessageId(Long userId, Long sessionId, String clientMsgId);

    /** 消息落库成功后登记，供窗口内重发直接回 duplicate */
    void mark(Long userId, Long sessionId, String clientMsgId, Long messageId);
}
