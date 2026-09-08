package com.dsmarket.common.constant;

public interface RedisKeyConstant {

    String TOKEN_BLACKLIST = "dsm:token:blacklist:";
    String RATE_LIMIT_LOGIN = "dsm:rate:limit:";
    String ORDER_SEQ = "dsm:order:seq:";
    String CART_CACHE = "dsm:cart:cache:";

    /** AI 会话轮条目（每用户单键，REQ C1-F1；值=JSON 轮条目数组） */
    String AI_SESSION = "dsm:ai:session:";
    /** AI 单飞行锁（同用户仅一处在途生成，REQ C1 E12） */
    String AI_INFLIGHT = "dsm:ai:inflight:";
    /** AI /chat 限流计数（固定窗口，REQ C1 §9：20 次/分） */
    String AI_CHAT_LIMIT = "dsm:ai:chat:limit:";
    /** AI /chat clientMsgId 幂等键（REQ C1 §2：30s 内重复 → 200 done） */
    String AI_CHAT_DEDUP = "dsm:ai:chat:dedup:";
}
