package com.dsmarket.modules.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把动作推迟到<b>事务提交之后</b>执行（C4 切片 2 引入）。
 *
 * <p>为什么需要它：SSE 推送是不可撤回的 —— 一旦发给客户端就收不回来。若在
 * {@code @Transactional} 方法体内推送，事务随后回滚（唯一约束冲突、并发 CAS 失败、
 * 后续语句出错）就会让买家看到一条<b>数据库里并不存在的消息</b>。先提交、后推送，
 * 语义上才是"我告诉你的事已经落定"。</p>
 *
 * <p>无事务上下文时（纯单测、无事务的内部调用）立即执行 —— 这是刻意的：
 * 此时不存在"未提交"状态，推迟反而会让断言看不到推送。</p>
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 推送失败不得反噬已提交的业务（§4.8：事件只是增量通知，权威在 PG）
                    log.warn("[ai][c4] 事务提交后推送失败（业务已落库，由快照兜底）. err={}", e.getMessage());
                }
            }
        });
    }
}
