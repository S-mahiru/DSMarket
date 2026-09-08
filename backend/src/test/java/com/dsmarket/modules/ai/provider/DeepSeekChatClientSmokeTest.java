package com.dsmarket.modules.ai.provider;

import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 在线冒烟：真实 key 打一次非工具单轮对话，验证 Provider 接线。
 *
 * <p>key 从环境变量 AI_MARKET（回落到 AI_ASSISTANT_API_KEY）读取，不落盘、不打印明文；
 * 未配置 key 时自动 SKIP，不会让常规 mvn test 失败。</p>
 */
class DeepSeekChatClientSmokeTest {

    @Test
    void chat_realModel_returnsReply() {
        String key = readKey();
        Assumptions.assumeTrue(key != null && !key.isBlank(), "未配置 LLM API Key，跳过在线冒烟");

        AiProperties props = new AiProperties();
        props.getLlm().setApiKey(key);

        DeepSeekChatClient client = new DeepSeekChatClient(props, new ObjectMapper());

        ChatResponse resp = client.chat(
                List.of(ChatMessage.builder()
                        .role(ChatRole.USER)
                        .content("你好，请只回复四个字：冒烟通过")
                        .build()),
                null);

        System.out.println("[smoke] finishReason=" + resp.getFinishReason());
        System.out.println("[smoke] reply=" + resp.getContent());
        assertNotNull(resp.getContent(), "模型应返回正文");
        assertFalse(resp.getContent().isBlank(), "正文不应为空");
        // 字节级校验：若响应按错误字符集解码（GBK 乱码），正文将不含预期中文
        assertTrue(resp.getContent().contains("冒烟通过"), "UTF-8 往返应保持中文原文，实际=" + resp.getContent());
    }

    private String readKey() {
        String k = System.getenv("AI_MARKET");
        return (k == null || k.isBlank()) ? System.getenv("AI_ASSISTANT_API_KEY") : k;
    }
}
