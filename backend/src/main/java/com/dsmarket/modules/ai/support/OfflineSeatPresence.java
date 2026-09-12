package com.dsmarket.modules.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 坐席存在性接缝的<b>关闭态实现</b>：恒判"无人在线"，在线基建整体不启用。
 *
 * <p><b>切片 2 起已不是缺省值</b>：切片 2 交付了 {@link RedisSupportSeatPresence}，
 * {@code ai.support.seat-presence} 缺省改为 {@code redis}。本实现退居为显式开发开关
 * （置 {@code offline} 生效），用途是<b>在没有坐席的环境里演示诚实降级路径</b>
 * —— 此时一切 request 都得到"当前暂无客服在线，请留言"，正是 H2 期望的行为。</p>
 *
 * <p>它也实现 {@link SeatOnlineRegistry}（全部 no-op/false），这样关闭在线基建时
 * 工作台接口仍在、只是永远没有坐席在线；{@link #markOnline} 会打警告日志，
 * 免得"心跳一直成功但没人上线"被误读成 bug。</p>
 *
 * <p>用显式属性开关而非 {@code @ConditionalOnMissingBean}：后者只对 auto-configuration 类可靠，
 * 放在普通 {@code @Component} 上依赖扫描顺序，两个实现可能同时注册导致注入歧义。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.support.seat-presence", havingValue = "offline")
public class OfflineSeatPresence implements SupportSeatPresence, SeatOnlineRegistry {

    @Override
    public boolean anySeatOnline() {
        return false;
    }

    @Override
    public boolean markOnline(Long adminId) {
        // 配置成 offline 时坐席在线基建未启用：心跳必然是空操作。
        // 明确告警而非静默——否则"心跳 200 却永远无人在线"会被当成故障排查半天。
        log.warn("[ai][c4] 坐席心跳被忽略：ai.support.seat-presence=offline，坐席在线基建未启用. adminId={}",
                adminId);
        return false;
    }

    @Override
    public void markOffline(Long adminId) {
        // 无键可清
    }

    @Override
    public boolean isOnline(Long adminId) {
        return false;
    }
}
