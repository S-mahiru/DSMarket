package com.dsmarket.modules.ai.task;

import com.dsmarket.modules.ai.service.AiSupportAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 等待接入超时定时器（C4 §4.3 / H3 / H28）。
 *
 * <p>对象是"坐席在线但迟迟没人接入的 pending_human 会话"，与 §4.2 那句"服务端不做主动心跳扫描推送"
 * <b>不是同一件事</b>（§4.3 主语消歧 L1-4 已明确）：这里扫的是<b>会话</b>，不是坐席在线键。</p>
 *
 * <p>扫得比阈值密（5s 一次 vs 30s 阈值）是为了让提示的到达延迟可控（最坏 35s）；
 * 提示本身只发一次，靠会话表里的 SYSTEM 提示存在性判定，与扫描频率无关。</p>
 *
 * <p>逻辑全在服务层（可单测），本类只是薄包装 —— 同 {@link AiIssueRetryTask} 的风格。
 * 兜底任务自身不可因偶发异常中断后续轮次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSupportWaitTimeoutTask {

    private final AiSupportAdminService adminService;

    /** 每 5s 扫一次：坐席在线 + pending_human 超 ai.support.wait-timeout（缺省 30s）→ 落 SYSTEM 提示 */
    @Scheduled(fixedDelay = 5000)
    public void sweepWaitTimeout() {
        try {
            int notified = adminService.sweepWaitTimeout();
            if (notified > 0) {
                log.info("[ai][c4] §4.3 等待超时提示：本次通知 {} 个会话", notified);
            }
        } catch (Exception e) {
            log.warn("[ai][c4] §4.3 等待超时扫描异常（本轮跳过，下轮再试）", e);
        }
    }
}
