package com.dsmarket.modules.ai.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiProperties 关键默认值锁（防建表前置项被误改）：
 * embedding 维度 1024 决定 ds_ai_knowledge.embedding vector(1024)；SSE 工具轮次上限 3。
 */
class AiPropertiesTest {

    @Test
    void embeddingDefaults_afterA1Decision() {
        AiProperties.Embedding e = new AiProperties().getEmbedding();
        assertEquals("dashscope", e.getVendor());
        assertEquals("text-embedding-v3", e.getModel());
        assertEquals(1024, e.getDimension(), "A1 已锁 1024：C2 建表 vector(1024)，勿改");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", e.getBaseUrl());
    }

    @Test
    void chatAndSessionDefaults() {
        AiProperties p = new AiProperties();
        assertEquals(3, p.getChat().getMaxToolRounds(), "REQ C1-F3 Agent 轮次上限 ≤3");
        assertEquals(20, p.getChat().getRateLimit());
        assertEquals(8, p.getSession().getMaxRounds());
        assertEquals(500, p.getChat().getContentMax(),
                "C1 §2/§9：AI 态 500（可调参数）；命中锚点则豁免，只受 ChatRequest 的绝对上限 4000 约束");
    }

    @Test
    void searchDefaults_weightedAndMagnitudeGate() {
        // 切片 4：档④加权默认 1.0=等权（不改变档③行为）；幅度闸默认 0=关闭（标定前无行为变化）
        AiProperties.Search s = new AiProperties().getSearch();
        assertEquals(1.0, s.getWBm25(), 1e-9, "档④默认 w_bm25=1 → 等权基线（档③），不改变现有行为");
        assertEquals(1.0, s.getWDense(), 1e-9, "档④默认 w_dense=1");
        assertEquals(0.0, s.getDenseMinSim(), 1e-9, "幅度闸默认 0=关闭；标定（train-only）后置定稿值");
    }

    @Test
    void issueDefaults_retryCap() {
        // 切片 2：F6 向量化兜底重试上限默认 5（达到上限 log.warn 告警，仍每分钟重试）
        assertEquals(5, new AiProperties().getIssue().getRetryMax(), "ai.issue.retry-max 默认 5");
    }

    @Test
    void supportDefaults_c4BuyerChannel() {
        // C4 §4.4/§3.2：人工通道限流低于 /chat（20），留言上限高于 /chat（500）——两处都别"顺手对齐"
        AiProperties.Support s = new AiProperties().getSupport();
        assertEquals(10, s.getRateLimit(), "人工态 10 次/分（§4.4，低于 /chat 的 20，防骚扰坐席）");
        assertEquals(5, s.getDedupTtl().toSeconds(), "cid 幂等窗口 5s（§4.4 P6）");
        assertEquals(2000, s.getUserContentMax(), "买家留言 ≤2000（§3.2，高于 /chat 的 500）");
        assertEquals(4000, s.getAgentContentMax(), "坐席回复 ≤4000（§3.2）");
    }

    @Test
    void supportDefaults_seatOnline() {
        // 切片 2（坐席在线 + 30s 无人接降级）落地：这些值直接决定"多久判离线/多久提示留言"
        AiProperties.Support s = new AiProperties().getSupport();
        assertEquals(15, s.getHeartbeat().toSeconds(), "心跳 15s（§4.2）");
        assertEquals(45, s.getSeatOfflineTtl().toSeconds(), "45s 无刷新判离线（§4.2）");
        assertEquals(30, s.getWaitTimeout().toSeconds(), "坐席在线 30s 无人接 → 提示留言（§4.3）");
        assertEquals("redis", s.getSeatPresence(),
                "切片 2 起缺省接缝 = redis（心跳驱动）；offline 退居为演示诚实降级的开发开关");
    }

    @Test
    void supportDefaults_streamAndSummary() {
        AiProperties.Support s = new AiProperties().getSupport();
        assertEquals(30, s.getStreamTimeout().toMinutes(),
                "人工态 SSE 是常驻通道，超时必须远长于 /chat 的 3m（否则买家抽屉开着就断）");
        assertEquals(5, s.getAiSummaryRounds(), "§4.5 L 决议：工作台回放最近 5 轮 AI 原文");
    }

    @Test
    void supportDefaults_humanRequestAnchors() {
        AiProperties.Support s = new AiProperties().getSupport();
        assertEquals(9, s.getHumanRequestAnchors().size(), "主 REQ §5.1 HUMAN_REQUEST 权威词集共 9 条");
        assertTrue(s.getHumanRequestAnchors().containsAll(
                        List.of("人工客服", "人工", "真人", "转人工", "找个人", "找你们领导",
                                "投诉", "投诉电话", "12315")),
                "词集与 §5.1 逐条一致；少一条就会漏触发，多一条就会误触发");
        assertFalse(s.getHumanRequestExclusions().isEmpty(),
                "裸词锚点「人工」「真人」是子串，没有误伤剔除表必然误触发（§5.1 点名「人工呼吸」）");
        assertTrue(s.getHumanRequestExclusions().contains("人工呼吸"),
                "§5.1 原文点名的误伤口径必须在剔除表里");
    }

    @Test
    void supportDefaults_f6EmotionAnchors() {
        // C4 §4.6 F6 触发②的初始词表。2026-09-10 裁决：与 §5.1 重叠 → 一律直接转人工，不发 suggest 气泡。
        // 与锚点集**分开维护**：各自可追溯到需求条款，判定时合并（见 AnchorWordMatcher）。
        AiProperties.Support s = new AiProperties().getSupport();
        assertEquals(6, s.getHumanRequestEmotionAnchors().size(), "§4.6 初始词表 6 条（可调）");
        assertTrue(s.getHumanRequestEmotionAnchors().containsAll(
                        List.of("投诉", "气死", "你们骗人", "太差", "差评", "退款都不行")),
                "词表与 §4.6 逐条一致");
        assertTrue(s.getHumanRequestAnchors().contains("投诉") && s.getHumanRequestEmotionAnchors().contains("投诉"),
                "「投诉」同属两集——这正是当初的重叠陷阱，测试把它钉成事实而非注释");
    }

    @Test
    void evalDefaults_c5Tracing() {
        // C5 §1/§2：留痕默认开、落点相对工作目录、盐有本地占位
        AiProperties.Eval e = new AiProperties().getEval();
        assertTrue(e.isEnabled(), "留痕默认开（§6.5 的对照实验才是例外，得显式关）");
        assertEquals("logs/ai-eval", e.getDir(), "§1 落点；相对进程工作目录，不绑死机器绝对路径");
        assertEquals(500, e.getContentMax(), "§2 content「≤500」");
        assertFalse(e.getHashSalt().isBlank(),
                "盐缺失会让 userIdHash 退化成可枚举的裸哈希 —— 绝不留空默认");
    }
}
