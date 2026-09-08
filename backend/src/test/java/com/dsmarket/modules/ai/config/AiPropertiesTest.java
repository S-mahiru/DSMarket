package com.dsmarket.modules.ai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }

    @Test
    void searchDefaults_weightedAndMagnitudeGate() {
        // 切片 4：档④加权默认 1.0=等权（不改变档③行为）；幅度闸默认 0=关闭（标定前无行为变化）
        AiProperties.Search s = new AiProperties().getSearch();
        assertEquals(1.0, s.getWBm25(), 1e-9, "档④默认 w_bm25=1 → 等权基线（档③），不改变现有行为");
        assertEquals(1.0, s.getWDense(), 1e-9, "档④默认 w_dense=1");
        assertEquals(0.0, s.getDenseMinSim(), 1e-9, "幅度闸默认 0=关闭；标定（train-only）后置定稿值");
    }
}
