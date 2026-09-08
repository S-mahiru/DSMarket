package com.dsmarket.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 智能客服配置（前缀 ai.*）。
 *
 * <p>所有"可调参数"集中在配置层，实验/答辩口径与代码解耦：
 * 调权网格、阈值、超时只改 yml/环境变量，不碰业务代码。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    /** 会话上下文（Redis 轮条目，REQ C1-F1） */
    private Session session = new Session();
    /** 单飞行（同用户并发去重，REQ C1 E12） */
    private Inflight inflight = new Inflight();
    /** SSE /chat 正式通道（REQ C1 §3/§9） */
    private Chat chat = new Chat();
    /** C2 双路检索引擎参数（REQ-20260907 C2 §3/§9） */
    private Search search = new Search();

    /** LLM（OpenAI 兼容 Chat，可替换 Provider） */
    @Data
    public static class Llm {
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-chat";
        /** 从环境变量注入（AI_MARKET 或 AI_ASSISTANT_API_KEY），不落盘不提交 */
        private String apiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);
    }

    /**
     * 文本向量化。【A1 已拍板 2026-09-08】供应商=DashScope(阿里云百炼)、model=text-embedding-v3、
     * dimension=1024（compatible-mode /v1/embeddings 实测），见 DECISION-20260908-A1。
     * dimension 决定 ds_ai_knowledge.embedding vector(dim) 建表长度 —— C2 建表即 vector(1024)。
     */
    @Data
    public static class Embedding {
        private String vendor = "dashscope";
        private String model = "text-embedding-v3";
        /** 与模型实际输出严格一致（实测 1024）；维度断言不符即抛错，防配置漂移 */
        private int dimension = 1024;
        /** OpenAI 兼容入口（与 Chat 同构，便于 C2 换实现/降级） */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        /** 对齐 C2 §3.2 Dense 路降级：connect 5s / read 15s */
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(15);
    }

    /** 会话上下文参数（REQ C1 §9：最近 8 轮 / 30min，均可调） */
    @Data
    public static class Session {
        /** 轮条目在 Redis 的存活时长（滚动刷新） */
        private Duration ttl = Duration.ofMinutes(30);
        /** 组装给模型的历史轮数上限 */
        private int maxRounds = 8;
    }

    /** AI 单飞行锁（REQ C1 E12） */
    @Data
    public static class Inflight {
        /** 锁兜底 TTL：仅用于进程异常退出时自动释放；正常路径在 finally 释放 */
        private Duration ttl = Duration.ofMinutes(15);
    }

    /** SSE /chat 正式通道参数（REQ C1 §9 可调参数） */
    @Data
    public static class Chat {
        /** Agent 工具轮次上限（REQ C1-F3：≤3，达上限仍要工具 → error TOO_MANY_TOOL_ROUNDS） */
        private int maxToolRounds = 3;
        /** 每用户每分钟可发消息数（REQ §9：20 次/分；超出 → 开流前 HTTP 429） */
        private int rateLimit = 20;
        /** 限流窗口 */
        private Duration rateWindow = Duration.ofMinutes(1);
        /** clientMsgId 幂等回放保留时长（REQ §2：30s 内重复 → 200 done） */
        private Duration dedupTtl = Duration.ofSeconds(30);
        /** SseEmitter 服务端超时（到时自动 complete，避免死连接） */
        private Duration sseTimeout = Duration.ofMinutes(3);
    }

    /**
     * C2 双路检索引擎参数（REQ-20260907 C2 §9 全部标"可调参数"，落地为配置）。
     * 调权/阈值实验只改 yml/env，不碰业务代码（对齐 REQ §7 调参纪律）。
     */
    @Data
    public static class Search {
        /** BM25 路召回数（C2 §3.1/F1 TOPK_BM25） */
        private int topkBm25 = 20;
        /** Dense 路召回数（C2 §3.2/F2 TOPK_DENSE） */
        private int topkDense = 20;
        /** RRF 融合常数（C2 §3.3/F3 RRF_K，score=Σ 1/(K+rank)） */
        private int rrfK = 60;
        /** 融合后取 top N 作候选（C2 §3.3/F3 FINAL_TOP → F6 输入） */
        private int finalTop = 10;
        /** 置信闸阈值（C2 §3.4/F4 CONF_MIN 初值；top1 融合分低于它 → 低置信返回空） */
        private double confMin = 0.015;
        /** RRF 加权（档④，C2 §7）：w_bm25 默认 1.0 = 等权基线（档③）；网格调参只改配置不碰代码 */
        private double wBm25 = 1.0;
        /** RRF 加权（档④，C2 §7）：w_dense 默认 1.0 = 等权基线（档③） */
        private double wDense = 1.0;
        /**
         * 相似度幅度闸（F4 #3，闭合 DECISION-20260908-C2 §2 gap）：top1 在 Dense 路有席位(dr&gt;0)时，
         * 其余弦相似度(1 - pgvector 距离)低于该值 → 无关问句/低置信。0=关闭（默认，标定前无行为变化）；
         * 标定（C2 §7 调参纪律 train-only）后置定稿值。
         */
        private double denseMinSim = 0.0;
    }
}
