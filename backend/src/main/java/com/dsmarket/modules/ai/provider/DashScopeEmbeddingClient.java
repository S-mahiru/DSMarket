package com.dsmarket.modules.ai.provider;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.ai.config.AiProperties;
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
 * DashScope（阿里云百炼）文本向量化客户端 —— 【A1 已拍板 2026-09-08】。
 *
 * <p>OpenAI 兼容入口 {@code /v1/embeddings}，模型 {@code text-embedding-v3}，实测 dim=1024
 * （见 DECISION-20260908-A1）。与 {@link DeepSeekChatClient} 同为 JDK HttpClient，无额外依赖。</p>
 *
 * <p>失败语义：网络/超时/维度不符一律抛 {@link BusinessException}（由 C2 Dense 路捕获后
 * 整体跳过、仅走 BM25 降级，不抛错给对话——见 C2 §3.2）。</p>
 */
@Slf4j
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final String EMBEDDINGS_PATH = "/embeddings";
    /** 单请求批量上限（保护请求体与超时预算） */
    private static final int BATCH = 16;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeEmbeddingClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getEmbedding().getConnectTimeout())
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    private List<float[]> embedBatch(List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getEmbedding().getModel());
        ArrayNode inputs = body.putArray("input");
        for (String t : texts) {
            inputs.add(t == null ? "" : t);
        }
        String json = post(EMBEDDINGS_PATH, body);
        try {
            return parseEmbeddings(objectMapper.readTree(json), properties.getEmbedding().getDimension());
        } catch (IOException e) {
            log.warn("向量化响应解析失败: {}", e.getMessage());
            throw new BusinessException(502, "向量化响应解析失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 请求

    private String post(String path, ObjectNode body) {
        AiProperties.Embedding emb = properties.getEmbedding();
        String baseUrl = emb.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(500, "ai.embedding.base-url 未配置");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(emb.getReadTimeout())
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
            String err = resp.body() == null ? "" : resp.body();
            if (err.length() > 300) {
                err = err.substring(0, 300);
            }
            throw new BusinessException(502, "向量化服务调用失败 HTTP " + resp.statusCode() + "：" + err);
        } catch (BusinessException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            log.warn("向量化调用超时: {}", e.getMessage());
            throw new BusinessException(500, "向量化调用超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "向量化调用被中断");
        } catch (IOException e) {
            log.warn("向量化调用网络异常: {}", e.getMessage());
            throw new BusinessException(500, "向量化调用网络异常：" + e.getMessage());
        }
    }

    private String apiKey() {
        String key = properties.getEmbedding().getApiKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(500,
                    "Embedding API Key 未配置：请设置环境变量 DASHSCOPE_API_KEY（或旧拼写 DASHSCOPE_API_KRY）");
        }
        return key;
    }

    // ------------------------------------------------------------ 解析（纯逻辑，便于单测）

    /**
     * 解析 OpenAI 兼容 embeddings 响应：按 {@code data[].embedding} 原序返回 float 向量，
     * 并断言每个向量长度 = expectedDim（>0 时）。维度不符 = 配置/模型漂移，直接抛错。
     */
    static List<float[]> parseEmbeddings(JsonNode root, int expectedDim) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new BusinessException(502, "向量化响应缺少 data 数组");
        }
        List<float[]> out = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode arr = item.path("embedding");
            if (!arr.isArray() || arr.isEmpty()) {
                throw new BusinessException(502, "向量化响应项缺少 embedding 数组");
            }
            if (expectedDim > 0 && arr.size() != expectedDim) {
                throw new BusinessException(500,
                        "向量维度不符：配置 dimension=" + expectedDim + "，模型返回 " + arr.size());
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            out.add(v);
        }
        return out;
    }
}
