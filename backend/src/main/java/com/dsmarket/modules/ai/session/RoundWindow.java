package com.dsmarket.modules.ai.session;

import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话轮窗口的纯逻辑（不依赖 Redis/Spring，便于单测）：截断 / 签发 replyId / 展开为模型消息。
 *
 * <p>规则源自 REQ C1 §4.1：轮条目数组截断为最近 N 轮；给模型前展开为
 * system + user/assistant 交替 + 当轮 user。</p>
 */
public final class RoundWindow {

    private RoundWindow() {
    }

    /** 保留最近 maxRounds 轮（丢弃更早的）。入参不修改。 */
    public static List<ChatRound> trim(List<ChatRound> rounds, int maxRounds) {
        if (rounds == null || rounds.isEmpty()) {
            return new ArrayList<>();
        }
        int keep = Math.min(rounds.size(), Math.max(0, maxRounds));
        return new ArrayList<>(rounds.subList(rounds.size() - keep, rounds.size()));
    }

    /**
     * 签发新一轮 replyId：取末轮 replyId + 1（会话内单调递增）；
     * 无历史 → "1"；末轮不可解析（脏数据）→ 按条数兜底。
     */
    public static String nextReplyId(List<ChatRound> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return "1";
        }
        ChatRound last = rounds.get(rounds.size() - 1);
        if (last.getReplyId() != null) {
            try {
                return Integer.toString(Integer.parseInt(last.getReplyId()) + 1);
            } catch (NumberFormatException ignored) {
                // 脏数据：fallthrough 兜底
            }
        }
        return Integer.toString(rounds.size() + 1);
    }

    /**
     * 按 replyId 反查轮条目（主 REQ §2.4 feedback 回查数据源）。
     * 会话窗口物理只留最近 maxRounds 轮（Redis 值已被 trim）+ TTL 滚动 → 超窗回复返回 null，
     * 调用方据此按"回查失败 → 200 静默忽略"处理，不额外持久化旁路。
     */
    public static ChatRound findRound(List<ChatRound> rounds, String replyId) {
        if (rounds == null || rounds.isEmpty() || replyId == null) {
            return null;
        }
        for (ChatRound round : rounds) {
            if (replyId.equals(round.getReplyId())) {
                return round;
            }
        }
        return null;
    }

    /**
     * 把历史轮次 + 当轮问句组装成发给模型的 messages：
     * system（新鲜注入，不入库）→ 每轮 user/assistant 交替 → 末尾当前 user。
     */
    public static List<ChatMessage> expand(List<ChatRound> history, String systemPrompt,
                                           String userText, int maxRounds) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role(ChatRole.SYSTEM).content(systemPrompt).build());
        for (ChatRound round : trim(history, maxRounds)) {
            if (hasText(round.getUserContent())) {
                messages.add(ChatMessage.builder().role(ChatRole.USER).content(round.getUserContent()).build());
            }
            if (hasText(round.getAssistantContent())) {
                messages.add(ChatMessage.builder().role(ChatRole.ASSISTANT).content(round.getAssistantContent()).build());
            }
        }
        messages.add(ChatMessage.builder().role(ChatRole.USER).content(userText).build());
        return messages;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
