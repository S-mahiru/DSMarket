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
    /**
     * AI 未解决信号计数（REQ C4 §4.6 F6 触发③：本会话累计"低置信轮 + 点踩"次数，达阈值 → suggest(UNRESOLVED)）。
     * 值=计数；TTL 取 {@code ai.session.ttl}（与 AI 会话同寿）；转人工时清零。
     */
    String AI_UNRESOLVED = "dsm:ai:unresolved:";

    /** 人工态发消息限流计数（REQ C4 §4.4：10 次/分/用户，低于 /chat 的 20，防骚扰坐席） */
    String AI_SUPPORT_RATE_LIMIT = "dsm:ai:support:rl:";
    /** 人工态 clientMsgId 幂等键（REQ C4 §4.4 P6：5s 内重复 → 200 不重落库） */
    String AI_SUPPORT_DEDUP = "dsm:ai:support:dedup:";

    /** 坐席在线键（REQ-20260907-C4 §4.2：{@code dsm:ai:seat:{ADMIN_ID}}，心跳 15s 刷新、TTL 45s 判离线） */
    String AI_SEAT = "dsm:ai:seat:";
    /**
     * 坐席在线键的<b>成员索引</b>（REQ 未指定，实施补充）：SET 存"可能在线"的 adminId 集合，
     * 让 {@code anySeatOnline()} 无需 {@code KEYS dsm:ai:seat:*} 全库扫描（O(N) 会阻塞单线程 Redis）。
     * 该索引<b>不是</b>在线判定的权威——权威仍是各 adminId 的键是否存在（见 RedisSupportSeatPresence）。
     */
    String AI_SEAT_INDEX = "dsm:ai:seat:index";
}
