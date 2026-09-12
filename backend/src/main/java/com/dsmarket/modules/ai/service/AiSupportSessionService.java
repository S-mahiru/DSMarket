package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.dto.AiSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;

/**
 * 人工客服会话服务（C4 切片 1：买家侧闭环）。
 *
 * <p><b>结构上不经 LLM</b>：实现类的依赖里没有 ChatModel / EmbeddingClient / AiSessionStore /
 * AiIssueService —— 人工态消息一律不进模型，这是 REQ §4.4 硬规则的编译期保证，不是靠运行时 if。</p>
 *
 * <p>切片 3 起本接口多两个方法：{@link #requestByAnchor}（服务端内部转人工途径）与
 * {@link #hasHumanTakeover}（竞态复查用的只读探针）。锚点正文的截断与转存编排在
 * {@code ChatHumanRouter} —— 它必须经本接口的<b>代理</b>逐次调用才能让 {@code sendMessage}
 * 的事务生效，所以那一步不放在本类内部。</p>
 */
public interface AiSupportSessionService {

    /**
     * 发起转人工（C4-F1）。幂等：该买家已有 PH 会话 → 返回现有会话现状，不新建（H7）；
     * 仅有已 closed 会话 → 新建（H12）。
     *
     * @param origin 触发途径，仅接受 {@code USER_REQUEST} / {@code AI_SUGGEST}；其他值 400
     *               （{@code ANCHOR_HIT} 是服务端内部途径，不接受买家自报）
     */
    SupportRequestResult request(Long userId, String origin);

    /**
     * 人工态发消息（C4-F4，PH 三态通用）。会话不存在 → 409 NO_ACTIVE_SESSION；
     * 已 closed → 409 SESSION_CLOSED 且不落库（H18）；超限 → 429。
     */
    SupportSendResult sendMessage(Long userId, String content, String clientMsgId);

    /** 买家侧会话快照（C4 §4.4 会话恢复）——人工会话历史的唯一来源，SSE 不重放历史 */
    AiSupportSessionVO snapshot(Long userId);

    /**
     * 服务端内部转人工（C4 切片 3，§4.1 入口分流②）：语义与 {@link #request} 完全一致，
     * 唯一差别是 origin 恒为 {@code ANCHOR_HIT}。
     *
     * <p><b>为什么单独开一个方法而不是让 request 收 ANCHOR_HIT</b>：那会把"服务端内部途径"
     * 和"买家可自报途径"合成同一个入口，某天有人把 controller 的 origin 直通进来，
     * 买家就能伪造锚点来源。拆开后 {@link #request} 的入参校验保持封闭，
     * 本方法也<b>不得</b>暴露到任何面向买家的 controller。</p>
     */
    SupportRequestResult requestByAnchor(Long userId);

    /**
     * 该 userId 此刻是否处在人工接管集合 PH（只读探针，不改任何状态）。
     *
     * <p>两个用途：{@code /chat} 的入口分流（§2.1 ①）与生成过程的竞态复查
     * （主 REQ §4.2 规则 11 / C1「写回前复查会话状态」）。</p>
     */
    boolean hasHumanTakeover(Long userId);
}
