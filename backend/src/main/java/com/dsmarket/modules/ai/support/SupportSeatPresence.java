package com.dsmarket.modules.ai.support;

/**
 * 坐席在线存在性接缝（C4）。
 *
 * <p>把"有没有真人在线"抽成一个布尔查询，让状态机与控制器<b>完全不感知坐席基建</b>
 * （Redis 在线键 {@code dsm:ai:seat:*}、心跳、多标签共享等细节）。切片 1 用
 * {@link OfflineSeatPresence} 恒返回 false，于是所有路径都走"无人在线 → 提示留言 → message_left"
 * 这条诚实降级分支；切片 2 换成 Redis 实现即可让在线分支生效，<b>调用方一行不用改</b>。</p>
 */
public interface SupportSeatPresence {

    /**
     * 当前是否有任一坐席在线（{@code ai:seat:*} 存在未过期的在线键）。
     *
     * <p>本方法只回答"有没有人"，不回答"是谁"—— 本模块不做多坐席路由/排队分配（REQ §1 明确不做）。</p>
     */
    boolean anySeatOnline();
}
