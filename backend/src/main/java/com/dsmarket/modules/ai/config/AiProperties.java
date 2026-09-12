package com.dsmarket.modules.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

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
    /** 问题池兜底重试（C3-F6，REQ I9/I10） */
    private Issue issue = new Issue();
    /** C4 转人工与坐席工作台（REQ-20260907-C4 §4/§9） */
    private Support support = new Support();
    /** C5 效果评估与留痕（REQ-20260908-C5 §1/§2） */
    private Eval eval = new Eval();

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
        /**
         * AI 态用户问句上限（C1 §2/§9：500，可调参数）。超此值<b>且未命中转人工锚点</b> → 开流前
         * HTTP 400。锚点命中则豁免本上限（长申诉可达转人工口子），但仍受 {@code ChatRequest} 的
         * 绝对上限 4000 约束。校验点在 {@code AiChatStreamService.open()}，<b>排在分流判定之后</b>
         * ——C1 §2 明文"锚点检测先于长度校验"。
         */
        private int contentMax = 500;
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

    /**
     * 问题池 F6 定时向量化兜底重试参数（REQ-20260907 C3 I9/I10：采纳受理但向量化失败 → 每分钟重试，
     * 单条达上限告警；达上限仍保留每分钟重试，修复后自动发布，不静默丢）。
     */
    @Data
    public static class Issue {
        /** 单条向量化兜底重试上限（达上限 log.warn 告警一次） */
        private int retryMax = 5;
    }

    /**
     * C4 转人工与坐席工作台参数（REQ-20260907-C4 §9 全部标"可调参数"，落地为配置）。
     * 切片 1 用 rateLimit/rateWindow/dedupTtl/userContentMax；切片 2 起其余全部生效。
     */
    @Data
    public static class Support {
        /**
         * 坐席在线接缝实现选择。{@code redis} = 生产实现（心跳驱动，切片 2 起缺省）；
         * {@code offline} = 恒无人在线，用于在没有坐席的环境演示诚实留言降级。
         * 注意：该值由 {@code @ConditionalOnProperty} 在容器装配期读取，<b>不注入本类</b>，
         * 此处仅作口径留痕，改配置改的是 yml。
         */
        private String seatPresence = "redis";
        /** 人工态发消息限流：每用户每分钟条数（§4.4，低于 /chat 的 20，防骚扰坐席） */
        private int rateLimit = 10;
        /** 人工态限流窗口 */
        private Duration rateWindow = Duration.ofMinutes(1);
        /** clientMsgId 幂等保留时长（§4.4 P6：5s 内重复 → 200 不重落库；缺省则不做内容去重） */
        private Duration dedupTtl = Duration.ofSeconds(5);
        /** 买家留言正文上限（§3.2：≤2000，与 /chat 的 500 不同——人工通道允许完整申诉） */
        private int userContentMax = 2000;
        /** 坐席回复正文上限（§3.2：≤4000） */
        private int agentContentMax = 4000;
        /** 坐席心跳间隔（§4.2：15s 刷新在线键） */
        private Duration heartbeat = Duration.ofSeconds(15);
        /** 坐席在线键 TTL（§4.2：45s 无刷新判离线） */
        private Duration seatOfflineTtl = Duration.ofSeconds(45);
        /** 坐席在线但无人接入的等待阈值（§4.3：30s 后提示可留言） */
        private Duration waitTimeout = Duration.ofSeconds(30);
        /**
         * 双 SSE 通道的 SseEmitter 服务端超时（REQ §4.7/§4.8 未标数值，实施取值）。
         * 人工通道是<b>常驻</b>通道（不像 /chat 一轮即完），故远长于 chat.sseTimeout；
         * 到时 SseEmitter 自动 complete，前端按 §4.8 "重连 = 先快照后增量"重连，不丢消息。
         */
        private Duration streamTimeout = Duration.ofMinutes(30);
        /**
         * 工作台会话详情里回放的 AI 对话轮数（§4.5 L 决议 N=5：读 Redis AI 会话原文直读，
         * <b>不加 LLM 生成、不加成本</b>；过期/空 → 显示"无 AI 对话记录"）。
         */
        private int aiSummaryRounds = 5;
        /**
         * 转人工锚点词集（主 REQ §5.1 HUMAN_REQUEST，权威完整）。子串匹配。
         * §10 A10 把"锚点词集"列为<b>误伤率</b>可调项 → 收敛口径只改配置，不碰代码。
         */
        private List<String> humanRequestAnchors = List.of(
                "人工客服", "人工", "真人", "转人工", "找个人", "找你们领导", "投诉", "投诉电话", "12315");
        /**
         * F6 情绪/投诉锚点词集（C4 §4.6 触发②的初始词表，2026-09-10 裁决）。
         *
         * <p><b>裁决口径</b>：§4.6 原设计是命中 → 只加 {@code suggest} 气泡（会话仍 ai_active，
         * H5：点后才转）。但其中"投诉"与 §5.1 权威 HUMAN_REQUEST 词集<b>重叠</b>，同一句话在两条
         * 规则下行为相反。2026-09-10 拍到：<b>重叠按"直接转人工"处理</b> —— 情绪词集整体并入
         * 自动转人工触发口（origin 仍记 {@code ANCHOR_HIT}，它确实是"content 命中词集"），
         * <b>不再下发 suggest 气泡</b>。这是对 §4.6 触发②的<b>有意收窄</b>，记于
         * DECISION-20260910-C4-切片3 裁决项。</p>
         *
         * <p>词表本身仍按 §4.6"可调"：收敛误伤只改配置，不碰代码。</p>
         */
        private List<String> humanRequestEmotionAnchors = List.of(
                "投诉", "气死", "你们骗人", "太差", "差评", "退款都不行");
        /**
         * 锚点匹配的<b>已知误伤口径</b>：匹配前先从原文中剔除（替换为空格）再判定。
         *
         * <p>裸词锚点 {@code 人工} / {@code 真人} 误伤面最大：前者怕"人工呼吸/人工智能"，
         * 后者怕"真人秀/真人版/真人CS"（电商语境里问真人 CS 装备完全正常）。§5.1 原文点名了
         * "人工呼吸"。<b>本表是初版种子</b>，需按 §10 A10 用真实问句收敛误伤率。</p>
         */
        private List<String> humanRequestExclusions = List.of(
                "人工呼吸", "人工智能", "人工湖", "人工降雨", "人工增雨",
                "真人秀", "真人版", "真人CS", "真人cs");
        /**
         * F6 触发③（未解决≥2）的阈值：本会话累计"低置信轮 + 点踩"达到该值 → 下发
         * {@code suggest("UNRESOLVED")} 气泡（§4.6 原文"≥2 次"）。会话仍 ai_active，点后才转（H5）。
         *
         * <p>计数落 Redis（{@code dsm:ai:unresolved:{userId}}，TTL = {@code ai.session.ttl}），
         * <b>不数 {@code ds_ai_issue_pool}</b>——理由见 {@code UnresolvedSignalCounter} 的类注释
         * （无 session_id / 去重口径会漏掉复问 / 停采后越成功数得越少）。</p>
         */
        private int unresolvedThreshold = 2;
    }

    /**
     * C5 效果评估与留痕参数（REQ-20260908-C5 §1/§2）。
     *
     * <p>留痕是<b>纯观察</b>：写日志失败只记日志，绝不改变对话/转人工语义与事件协议
     * （§1「不改既有行为」）。因此这里的参数<b>没有一个是行为开关</b>（除 {@code enabled}
     * 之外），全是"记什么、记多长"的口径项。</p>
     */
    @Data
    public static class Eval {
        /**
         * 留痕总开关。{@code false} = 一条都不落盘（§6.5 验收项「关闭日志开关后 AI/转人工
         * 全部行为不变（回归）」靠它做对照）。注意：关掉的是<b>落盘</b>，不是埋点本身
         * —— 埋点代码照跑，只是 {@code finishRound} 提前返回。这样开关两侧跑的是同一份
         * 生产代码路径，对照才有意义。
         */
        private boolean enabled = true;
        /**
         * JSONL 落盘目录（§1「logs/ai-eval/」），相对<b>进程工作目录</b>。
         * 用相对路径是刻意的：harness/IDE 从 backend 目录启动时落到
         * {@code backend/logs/ai-eval/}，不依赖任何一台机器的绝对路径。
         */
        private String dir = "logs/ai-eval";
        /**
         * {@code userIdHash} 的盐（HMAC-SHA256，见 {@code UserHash}）。
         *
         * <p><b>改盐即断链</b>：全部历史哈希与新日志对不上，P2-H3 沉淀曲线没法按用户 join。
         * 论文数据一旦开始采，此值就固定。缺省值只是本地环境的占位（§7 已把"敏感字段本地
         * 环境存储"列为前提），真接真人数据前应从环境变量注入。</p>
         */
        private String hashSalt = "dsmarket-ai-eval-local";
        /** 日志里问句原文的截断上限（§2 content「≤500」的落地；只影响留痕，不影响对话） */
        private int contentMax = 500;
        /**
         * 日志里<b>答复原文</b>的截断上限（§2 未列键 {@code answer} 的落地）。
         *
         * <p>取 2000 而非与问句同档的 500：问句有 500 的产品上限，答复没有；而标注者要判断
         * "答得对不对"，截太短会把长答复的后半段判据切掉、反而制造错误的低分。2000 足够覆盖
         * 常见答复全文，又不至于让单行日志膨胀到不可读。超出部分不静默丢弃 ——
         * 会写成可见标记（见 {@code RoundTrace#appendAnswer}）。</p>
         */
        private int answerMax = 2000;
        /**
         * {@code eventSeq} 最多记几个事件，超出折叠为 {@code …+N}。
         *
         * <p>需要封顶的原因：问句有 500 字上限，但<b>模型答复长度没有上限</b>；
         * 一句长答复按 {@code DeltaChunker.MAX_CHUNK=48} 切能出几十个 delta。
         * 40 = 常见答复（≤3 工具 + 十余个 delta + 收尾）留出充裕余量，又能拦住长答复膨胀。</p>
         */
        private int eventSeqMax = 40;
    }
}
