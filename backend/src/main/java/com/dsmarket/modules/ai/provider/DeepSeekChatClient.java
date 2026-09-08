package com.dsmarket.modules.ai.provider;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.model.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Chat 客户端（OpenAI 兼容 /chat/completions，function-calling）。
 *
 * <p>仅实现非流式单轮；SSE 流式属于 C1（SSE 通道）里程碑，届时扩展接口与实现。
 * 使用 JDK HttpClient，不引入额外 HTTP 依赖。</p>
 */
@Slf4j
@Component
public class DeepSeekChatClient implements ChatModel {

    private static final String COMPLETIONS_PATH = "/chat/completions";

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekChatClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getLlm().getConnectTimeout())
                .build();
    }

    @Override
    public String modelName() {
        return properties.getLlm().getModel();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelName());
        body.put("stream", false);

        ArrayNode msgArr = body.putArray("messages");
        for (ChatMessage m : messages) {
            msgArr.add(serializeMessage(m));
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArr = body.putArray("tools");
            for (ToolSpec t : tools) {
                toolArr.add(serializeTool(t));
            }
        }

        String json = post(COMPLETIONS_PATH, body);
        return parseNonStream(json);
    }

    // ------------------------------------------------------------------ 请求

    private String post(String path, ObjectNode body) {
        String baseUrl = properties.getLlm().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(500, "ai.llm.base-url 未配置");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(properties.getLlm().getReadTimeout())
                    .header("Authorization", "Bearer " + apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
            // 上游错误：只透出状态码与截断响应体，不回显完整报文
            String errBody = resp.body() == null ? "" : resp.body();
            if (errBody.length() > 300) {
                errBody = errBody.substring(0, 300);
            }
            throw new BusinessException(502,
                    "模型上游调用失败 HTTP " + resp.statusCode() + "：" + errBody);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "模型调用被中断");
        } catch (HttpTimeoutException e) {
            // 超时单独成类：流式编排据消息中的"超时"映射为 SSE error TIMEOUT（E5/E6）
            log.warn("模型调用超时: {}", e.getMessage());
            throw new BusinessException(500, "模型调用超时，请稍后重试");
        } catch (IOException e) {
            log.warn("模型调用网络异常: {}", e.getMessage());
            throw new BusinessException(500, "模型调用网络异常：" + e.getMessage());
        }
    }

    private String apiKey() {
        String key = properties.getLlm().getApiKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(500,
                    "LLM API Key 未配置：请设置环境变量 AI_MARKET 或 AI_ASSISTANT_API_KEY");
        }
        return key;
    }

    // ------------------------------------------------------------ 序列化

    private ObjectNode serializeMessage(ChatMessage m) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("role", m.getRole().name().toLowerCase());
        if (m.getContent() != null) {
            n.put("content", m.getContent());
        } else {
            n.putNull("content");
        }
        // ASSISTANT 消息原样带出本轮的 tool_calls，模型据此衔接工具结果
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            ArrayNode calls = n.putArray("tool_calls");
            for (ToolCall tc : m.getToolCalls()) {
                ObjectNode c = calls.addObject();
                c.put("id", tc.getId());
                c.put("type", "function");
                ObjectNode fn = c.putObject("function");
                fn.put("name", tc.getName());
                String args = (tc.getArguments() == null || tc.getArguments().isBlank())
                        ? "{}" : tc.getArguments();
                fn.put("arguments", args);
            }
        }
        // TOOL 结果回填
        if (m.getToolCallId() != null) {
            n.put("tool_call_id", m.getToolCallId());
        }
        return n;
    }

    private ObjectNode serializeTool(ToolSpec t) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("type", "function");
        ObjectNode f = n.putObject("function");
        f.put("name", t.getName());
        if (t.getDescription() != null && !t.getDescription().isBlank()) {
            f.put("description", t.getDescription());
        }
        JsonNode params = t.getParameters();
        if (params == null) {
            ObjectNode empty = f.putObject("parameters");
            empty.put("type", "object");
            empty.putObject("properties");
        } else {
            f.set("parameters", params);
        }
        return n;
    }

    // ------------------------------------------------------------ 解析

    private ChatResponse parseNonStream(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");

            String content = msg.hasNonNull("content") ? msg.get("content").asText() : null;
            String finishReason = choice.hasNonNull("finish_reason")
                    ? choice.get("finish_reason").asText() : null;

            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = msg.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    JsonNode fn = tc.path("function");
                    calls.add(ToolCall.builder()
                            .id(tc.path("id").asText(null))
                            .name(fn.path("name").asText(null))
                            .arguments(fn.hasNonNull("arguments") ? fn.get("arguments").asText() : null)
                            .build());
                }
            }
            return ChatResponse.builder()
                    .content(content)
                    .toolCalls(calls.isEmpty() ? null : calls)
                    .finishReason(finishReason)
                    .build();
        } catch (IOException e) {
            log.warn("模型响应解析失败: {}", e.getMessage());
            throw new BusinessException(502, "模型响应解析失败：" + e.getMessage());
        }
    }
}
