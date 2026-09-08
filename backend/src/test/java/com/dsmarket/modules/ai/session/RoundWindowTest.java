package com.dsmarket.modules.ai.session;

import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoundWindow 纯逻辑单测：轮条目截断 / replyId 单调签发 / 展开为模型消息。
 */
class RoundWindowTest {

    private ChatRound round(String replyId, String user, String assistant) {
        return ChatRound.builder()
                .replyId(replyId).userContent(user).assistantContent(assistant).ts(1L).build();
    }

    @Test
    void emptyRounds_replyIdStartsAtOne() {
        assertEquals("1", RoundWindow.nextReplyId(new ArrayList<>()));
        assertEquals("1", RoundWindow.nextReplyId(null));
    }

    @Test
    void replyId_monotonicIncrements() {
        List<ChatRound> rounds = new ArrayList<>();
        rounds.add(round("1", "q1", "a1"));
        rounds.add(round("2", "q2", "a2"));
        assertEquals("3", RoundWindow.nextReplyId(rounds));
    }

    @Test
    void trim_keepsLastMaxAndDoesNotMutateInput() {
        List<ChatRound> rounds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rounds.add(round(String.valueOf(i), "q" + i, "a" + i));
        }
        List<ChatRound> trimmed = RoundWindow.trim(rounds, 2);
        assertEquals(2, trimmed.size());
        assertEquals("4", trimmed.get(0).getReplyId());
        assertEquals("5", trimmed.get(1).getReplyId());
        assertEquals(5, rounds.size(), "trim 不应修改入参");
    }

    @Test
    void expand_buildsSystemThenHistoryThenCurrentUser() {
        List<ChatRound> history = new ArrayList<>();
        history.add(round("1", "问一", "答一"));
        history.add(round("2", "问二", "答二"));

        List<ChatMessage> msgs = RoundWindow.expand(history, "SYSTEM", "问三", 8);
        List<ChatRole> roles = msgs.stream().map(ChatMessage::getRole).toList();
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT,
                ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), roles);
        assertEquals("SYSTEM", msgs.get(0).getContent());
        assertEquals("问一", msgs.get(1).getContent());
        assertEquals("答一", msgs.get(2).getContent());
        assertEquals("问三", msgs.get(5).getContent());
    }

    @Test
    void expand_skipsBlankRoundContent() {
        List<ChatRound> history = new ArrayList<>();
        history.add(round("1", "问一", null));           // assistant 空 → 跳过（user 保留）
        history.add(ChatRound.builder().replyId("2")      // user 空 → 跳过（assistant 保留）
                .userContent("").assistantContent("答二").ts(1L).build());

        List<ChatMessage> msgs = RoundWindow.expand(history, "SYSTEM", "问三", 8);
        assertEquals(4, msgs.size(),
                "应含 system + 问一(round1.user) + 答二(round2.assistant) + 当轮问三");
        List<ChatRole> roles = msgs.stream().map(ChatMessage::getRole).toList();
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), roles);
        assertEquals("答二", msgs.get(2).getContent());
        assertEquals("问三", msgs.get(3).getContent());
    }

    @Test
    void expand_truncatesHistoryByMaxRounds() {
        List<ChatRound> history = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            history.add(round(String.valueOf(i), "q" + i, "a" + i));
        }
        List<ChatMessage> msgs = RoundWindow.expand(history, "SYSTEM", "cur", 2);
        String joined = msgs.stream().map(ChatMessage::getContent).reduce("", String::concat);
        assertFalse(joined.contains("q1"), "超出 maxRounds 的最旧轮不应出现");
        assertTrue(joined.contains("q4") && joined.contains("q5"), "应保留最近 2 轮");
        assertTrue(joined.endsWith("cur"));
    }
}
