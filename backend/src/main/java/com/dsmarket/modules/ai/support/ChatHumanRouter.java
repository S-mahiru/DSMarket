package com.dsmarket.modules.ai.support;

/**
 * {@code /chat} 入口的人工分流契约（C4 切片 3）。
 *
 * <p>拆成接口有两个实在的理由，不是为了形式：</p>
 * <ol>
 *   <li><b>可测</b>：{@code AiChatStreamService} 的单测用手写 stub（项目约定只 mock 接口），
 *       具体类会让装配退回 Mockito class-mocking；</li>
 *   <li><b>约束能力</b>：消费方只应"发起分流"和"复查是否已转人工"，不该拿到锚点词表、
 *       截断规则、提示文案这些实现细节——与切片 2 把坐席在线拆成
 *       {@code SupportSeatPresence} / {@code SeatOnlineRegistry} 是同一个理由（D1 决议）。</li>
 * </ol>
 *
 * @see ChatHumanRouterImpl
 */
public interface ChatHumanRouter {

    /**
     * 入口分流（主 REQ §2.1 硬规则，顺序：① PH 会话 → ② 锚点命中）。命中任一条即表示消息
     * 已进人工通道，调用方<b>不得</b>再走 AI（不调 LLM、不写回 Redis 会话）。
     *
     * @param clientMsgId 复用 {@code /chat} 的幂等键；人工通道的幂等键含 sessionId，两通道不会互相误命中
     * @return 分流结果；{@code null} = 未命中，照常走 AI 态
     */
    Routed route(Long userId, String content, String clientMsgId);

    /**
     * 该 userId 此刻是否处在人工接管集合 PH（只读探针，不改状态）。
     * 供生成过程的竞态复查使用（主 REQ §4.2 规则 11 / C1「写回前复查会话状态」）。
     */
    boolean hasHumanTakeover(Long userId);

    /**
     * 分流结果。
     *
     * @param sessionId 人工会话 id
     * @param status    写入后的人工会话状态（PH 之一）
     * @param notice    {@code /chat} 通道要回给买家的提示文案（走 fallback 事件）
     */
    record Routed(Long sessionId, String status, String notice) {
    }
}
