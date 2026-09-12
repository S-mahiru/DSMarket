package com.dsmarket.modules.ai.support;

import java.util.Map;

/**
 * 人工态实时事件发布接缝（C4 §4.7 买家通道 / §4.8 admin 通道）。
 *
 * <p>业务服务只依赖本接口，不感知 SSE、SseEmitter、连接注册表 —— 于是状态机的单测不需要起容器，
 * 用记录型替身就能断言"接入时买家收到了 human_status、工作台收到了 session_status"。</p>
 *
 * <p><b>语义是"尽力推送"，不是"送达保证"</b>（§4.8 收敛原则）：权威数据在 PG 两张表，
 * 事件只是增量通知；买家/坐席断线期间漏掉的事件由下次快照（{@code GET …/session}、
 * {@code GET …/sessions}）兜底，服务端<b>不做事件持久化与 Last-Event-ID 补发</b>。
 * 因此实现一律不抛异常给调用方：推不出去只记日志，绝不让"推不动"变成"业务失败"。</p>
 */
public interface SupportEventPublisher {

    /**
     * 推给某买家（买家人工态通道，仅 {@code human_*}）。
     *
     * @param type    事件类型，必须属于 §4.7 事件集；其他值视为代码缺陷
     * @param payload 载荷，用 {@link SupportEvents} 的工厂构造
     */
    void toBuyer(Long userId, String type, Map<String, Object> payload);

    /**
     * 广播给全部在线坐席（admin 通道，仅 {@code session_*}/{@code message_new}）。
     *
     * <p>{@code seat_status} 不走这里 —— 它只与某一个坐席有关，用 {@link #toAdmin}。</p>
     */
    void toAdmins(String type, Map<String, Object> payload);

    /** 推给某一个坐席（admin 通道，用于 {@code seat_status}：本人上线/登出只该通知本人） */
    void toAdmin(Long adminId, String type, Map<String, Object> payload);
}
