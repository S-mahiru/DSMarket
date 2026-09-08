package com.dsmarket.modules.ai.session;

/**
 * /chat clientMsgId 幂等（REQ C1 §2）：同 userId+clientMsgId 在 dedupTtl 内重复 →
 * 忽略并回 200 done（仅 replay 已完成轮次的 replyId）。
 */
public interface ChatDedupStore {

    /** 命中已完成的同 clientMsgId 轮 → 返回该轮 replyId；否则 null */
    String findReplyId(Long userId, String clientMsgId);

    /** 一轮正常 done 收尾后登记，供 30s 内重发直接回放 done */
    void mark(Long userId, String clientMsgId, String replyId);
}
