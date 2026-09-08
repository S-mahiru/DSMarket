package com.dsmarket.modules.ai.provider;

import java.util.List;

/**
 * 文本向量化客户端 —— 【A1 已拍板 2026-09-08】实现见 {@link DashScopeEmbeddingClient}。
 *
 * <p>供应商=DashScope、model=text-embedding-v3、dim=1024（实测，见 DECISION-20260908-A1）。
 * dimension 决定 ds_ai_knowledge.embedding vector(1024) 建表长度（C2 建表前置，已锁定）。
 * C2 检索/入库都经本接口，换实现不影响上层。</p>
 */
public interface EmbeddingClient {

    /**
     * 批量向量化。
     *
     * @return 与 texts 一一对应的向量列表；向量长度 = AiProperties.embedding.dimension
     */
    List<float[]> embed(List<String> texts);
}
