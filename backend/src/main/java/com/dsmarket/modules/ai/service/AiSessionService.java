package com.dsmarket.modules.ai.service;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.AiSingleFlight;
import com.dsmarket.modules.ai.session.RedisAiSessionStore;
import com.dsmarket.modules.ai.session.RedisAiSingleFlight;
import com.dsmarket.modules.ai.session.RoundWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 客服会话编排（REQ C1-F1 + C1 E12）：在无状态工具循环之上加「多轮记忆 + 单飞行」。
 *
 * <p>一次调用：<br>
 * ① 单飞行 {@code tryAcquire}（同 userId 已有在途生成 → HTTP 409 CONCURRENT_GENERATION）；<br>
 * ② 读 Redis 会话轮条目（缺省/失败 → 无历史继续）；<br>
 * ③ 展开为 system + 历史轮次 + 当轮问句 → 交给 {@link AiChatService} 工具循环；<br>
 * ④ 完成后签发 replyId，把 {@code {replyId, userContent, assistantContent}} 追加写回并刷新 TTL；<br>
 * ⑤ finally 释放单飞行锁。</p>
 *
 * <p>会话即每用户 Redis 单键（{@link RedisAiSessionStore}），无需前端传 sessionId（P7 决议）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSessionService {

    /** E12 提示话术（HTTP 409） */
    public static final String CONCURRENT_MESSAGE = "上一条消息正在回复中，请稍候再试。";

    private final AiSessionStore sessionStore;
    private final AiSingleFlight singleFlight;
    private final AiChatService chatService;
    private final AiProperties properties;

    public ChatTurnResult chat(Long userId, String userText) {
        if (!singleFlight.tryAcquire(userId)) {
            log.info("[ai] 单飞行拦截：userId={} 已有在途生成，回 409", userId);
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), CONCURRENT_MESSAGE);
        }
        try {
            List<ChatRound> history = sessionStore.loadRounds(userId);
            String replyId = RoundWindow.nextReplyId(history);
            List<ChatMessage> messages = RoundWindow.expand(
                    history, AiChatService.SYSTEM_PROMPT, userText, properties.getSession().getMaxRounds());

            ChatTurnResult result = chatService.handleMessages(userId, messages);
            result.setReplyId(replyId);
            sessionStore.appendRound(userId, ChatRound.of(replyId, userText, result.getReply()));
            return result;
        } finally {
            singleFlight.release(userId);
        }
    }
}
