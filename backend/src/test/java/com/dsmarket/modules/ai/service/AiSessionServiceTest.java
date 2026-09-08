package com.dsmarket.modules.ai.service;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.model.ToolSpec;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.AiSingleFlight;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.dsmarket.modules.ai.tool.ToolRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiSessionService 编排单测：多轮记忆写入与回放、replyId 签发、单飞行 409、异常释放锁。
 * 用内存替身 store/flight + StubChatModel，不依赖 Redis/LLM。
 */
class AiSessionServiceTest {

    /** 内存会话 store */
    static class MemoryStore implements AiSessionStore {
        private final Map<Long, List<ChatRound>> data = new HashMap<>();

        @Override
        public List<ChatRound> loadRounds(Long userId) {
            return new ArrayList<>(data.getOrDefault(userId, new ArrayList<>()));
        }

        @Override
        public void appendRound(Long userId, ChatRound round) {
            data.computeIfAbsent(userId, k -> new ArrayList<>()).add(round);
        }

        List<ChatRound> of(Long userId) {
            return data.getOrDefault(userId, new ArrayList<>());
        }
    }

    /** 内存单飞行：busy=true 模拟已有在途；记录 release 次数 */
    static class MemoryFlight implements AiSingleFlight {
        private boolean busy;
        private int released;

        MemoryFlight(boolean busy) {
            this.busy = busy;
        }

        @Override
        public boolean tryAcquire(Long userId) {
            if (busy) {
                return false;
            }
            busy = true;
            return true;
        }

        @Override
        public void release(Long userId) {
            busy = false;
            released++;
        }
    }

    /** 按调用次序吐出预设正文的 ChatModel，并快照每轮 messages */
    static class StubChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final List<List<ChatMessage>> seen = new ArrayList<>();
        private int idx;

        StubChatModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String modelName() {
            return "stub";
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(new ArrayList<>(messages));
            ChatResponse r = responses.get(Math.min(idx, responses.size() - 1));
            idx++;
            return r;
        }

        List<ChatMessage> messagesAt(int call) {
            return seen.get(call);
        }
    }

    /** 抛出异常的模型（测异常路径释放锁） */
    static class ThrowingChatModel implements ChatModel {
        @Override
        public String modelName() {
            return "throw-stub";
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            throw new IllegalStateException("上游炸了");
        }
    }

    private AiSessionService service(AiSessionStore store, AiSingleFlight flight, ChatModel model) {
        ToolRegistry registry = new ToolRegistry(List.of());
        AiChatService chatService = new AiChatService(model, registry,
                new ToolRunner(registry, new ObjectMapper()));
        return new AiSessionService(store, flight, chatService, new AiProperties());
    }

    @Test
    void chat_appendsRoundAndSignsReplyId_thenRemembersHistory() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().content("回答一").build(),
                ChatResponse.builder().content("回答二").build());
        AiSessionService svc = service(store, flight, model);

        ChatTurnResult r1 = svc.chat(100L, "第一问");
        assertEquals("回答一", r1.getReply());
        assertEquals("1", r1.getReplyId());
        assertEquals(1, store.of(100L).size());
        assertEquals("第一问", store.of(100L).get(0).getUserContent());
        assertEquals("回答一", store.of(100L).get(0).getAssistantContent());

        ChatTurnResult r2 = svc.chat(100L, "第二问");
        assertEquals("回答二", r2.getReply());
        assertEquals("2", r2.getReplyId(), "replyId 应单调递增");
        assertEquals(2, store.of(100L).size());

        // 第二次发给模型的 messages 应含首轮 user/assistant 历史（顺序：system→历史→当轮）
        List<ChatMessage> sent = model.messagesAt(1);
        List<ChatRole> roles = sent.stream().map(ChatMessage::getRole).toList();
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), roles);
        assertEquals("第一问", sent.get(1).getContent());
        assertEquals("回答一", sent.get(2).getContent());
        assertEquals("第二问", sent.get(3).getContent());
        assertEquals(2, flight.released, "每次正常完成后应释放单飞行锁");
    }

    @Test
    void concurrentInflight_throws409Conflict() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(true); // 已有在途生成
        AiSessionService svc = service(store, flight, new StubChatModel(
                ChatResponse.builder().content("x").build()));

        BusinessException ex = assertThrows(BusinessException.class, () -> svc.chat(100L, "再问"));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode(), "应回 409 CONCURRENT_GENERATION");
        assertEquals(0, flight.released, "未获得锁不应误释放");
        assertTrue(store.of(100L).isEmpty(), "被拦截的轮不应写入会话");
    }

    @Test
    void chatError_stillReleasesLock_andDoesNotAppend() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        AiSessionService svc = service(store, flight, new ThrowingChatModel());

        assertThrows(IllegalStateException.class, () -> svc.chat(100L, "会炸"));
        assertEquals(1, flight.released, "异常路径也必须 finally 释放锁");
        assertTrue(store.of(100L).isEmpty(), "异常轮不应写入会话（半截回答不留痕）");
    }
}
