package com.dsmarket.modules.ai.task;

import com.dsmarket.modules.ai.service.AiIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 问题池定时兜底（C3-F6 / REQ I9/I10）。
 *
 * <p>采纳受理但同步向量化失败的知识 draft 由本任务每分钟重试发布（@EnableScheduling 已在启动类）。
 * 与 {@code com.dsmarket.modules.order.task.OrderTask} 同风格：薄包装调服务可单测方法，逻辑不入 task。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiIssueRetryTask {

    private final AiIssueService issueService;

    /** 每 60s：向量化兜底重试（失败保留 draft，超 ai.issue.retry-max 告警；成功则自动发布并迁移 adopted） */
    @Scheduled(fixedDelay = 60000)
    public void retryVectorization() {
        try {
            int published = issueService.retryVectorization();
            if (published > 0) {
                log.info("[ai][c3] F6 定时向量化兜底：本次自动发布 {} 条", published);
            }
        } catch (Exception e) {
            // 兜底任务自身不可因偶发异常中断后续轮次
            log.warn("[ai][c3] F6 定时向量化兜底异常（本轮跳过，下轮再试）", e);
        }
    }
}
