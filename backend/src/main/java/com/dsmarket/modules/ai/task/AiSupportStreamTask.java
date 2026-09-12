package com.dsmarket.modules.ai.task;

import com.dsmarket.modules.ai.support.SupportStreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 人工态 SSE 通道 keepalive（C4 §4.7/§4.8）。
 *
 * <p>人工通道与 C1 的 {@code /chat} 不同：一次对话几十秒内就结束，而人工通道可能<b>长时间没有任何事件</b>
 * （买家在等坐席、坐席在等买家），中间的反向代理/容器很容易按空闲超时把连接掐掉。
 * 每 15s 发一个 SSE 注释帧（{@code ": ping"}）保住连接 —— 注释帧不是 data 事件，
 * 客户端的事件解析看不到它，因此不会污染 §4.7/§4.8 的事件白名单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSupportStreamTask {

    private final SupportStreamRegistry streams;

    /** 每 15s 一次：与坐席心跳同频（同一个"活性"时间尺度） */
    @Scheduled(fixedDelay = 15000)
    public void keepAlive() {
        try {
            streams.pingAll();
        } catch (Exception e) {
            log.warn("[ai][c4] SSE keepalive 异常（本轮跳过）", e);
        }
    }
}
