package com.dsmarket.modules.ai.support;

/**
 * 坐席在线态的<b>生命周期</b>操作（C4 切片 2）。
 *
 * <p>与 {@link SupportSeatPresence} 的分工：那个接口只回答状态机问的一句话"现在有没有人在线"，
 * 是状态机的窄接缝；本接口是工作台侧的写操作（心跳保活 / 显式登出 / 查某人在不在线）。
 * 两者都由同一个 Bean 实现（{@code RedisSupportSeatPresence}），但<b>用途不同的调用方依赖不同的接口</b>
 * —— 状态机拿不到"改在线态"的能力，就不可能在业务逻辑里顺手把坐席标成在线（REQ §1 底线：
 * 绝不谎报有真人）。</p>
 *
 * <p>切片 1 的 {@code OfflineSeatPresence} 也实现本接口（全部退化为 no-op/false），
 * 于是 {@code ai.support.seat-presence=offline} 时工作台接口仍在、只是永远没有坐席在线 —— 语义诚实，
 * 且不会因缺少实现类导致容器装配失败。</p>
 */
public interface SeatOnlineRegistry {

    /**
     * 心跳保活：写/刷新该坐席的在线键（TTL 取 {@code ai.support.seat-offline-ttl}）。
     *
     * @return {@code true} = 本次心跳<b>由离线转在线</b>（即首次心跳，admin 流据此推一条
     *         {@code seat_status(online, login)}）；{@code false} = 本来就在线，静默续期
     *         （多标签共享同一在线键，第二个标签心跳不该再推 login —— REQ §4.2 P5 决议无顶号互斥）
     */
    boolean markOnline(Long adminId);

    /** 显式登出：清在线键（§4.2 在线退出③）；键本就不存在则无副作用 */
    void markOffline(Long adminId);

    /** 该坐席此刻是否在线（键存在即在线；判定权威是键本身，非索引） */
    boolean isOnline(Long adminId);
}
