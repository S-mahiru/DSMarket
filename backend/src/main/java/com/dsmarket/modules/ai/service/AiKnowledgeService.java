package com.dsmarket.modules.ai.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.ai.dto.AiKnowledgeFormDTO;
import com.dsmarket.modules.ai.dto.AiKnowledgeQuery;
import com.dsmarket.modules.ai.dto.AiKnowledgeVO;

/**
 * AI 客服知识库后台服务（C2 / 主 REQ F6）。
 *
 * <p>状态机见 {@code com.dsmarket.modules.ai.enums.AiKnowledgeStatus}：create/编辑由服务端控制落
 * draft（编辑已 published 行 → 回退 draft，须重发布重向量化）；发布须同步向量化成功才置 published。</p>
 */
public interface AiKnowledgeService {

    PageResult<AiKnowledgeVO> adminPage(long page, long size, AiKnowledgeQuery query);

    AiKnowledgeVO detail(Long id);

    /** 新增：强制 draft，返回新条目 id。 */
    Long create(AiKnowledgeFormDTO form);

    /** 编辑：字段生效；若当前已 published → 回退 draft（重发布即重向量化）。 */
    void update(Long id, AiKnowledgeFormDTO form);

    /** 发布：draft/disabled→published。同步调 EmbeddingClient 向量化，成功才置 published；失败保持原状态并抛异常。 */
    void publish(Long id);

    /** 停用：published→disabled。 */
    void unpublish(Long id);

    /** 软删。 */
    void delete(Long id);
}
