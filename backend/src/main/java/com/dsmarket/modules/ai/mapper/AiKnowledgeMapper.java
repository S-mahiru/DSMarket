package com.dsmarket.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * AI 客服知识库 Mapper（C2）。
 *
 * <p>普通列读写走 MyBatis-Plus {@link BaseMapper}；{@code embedding} 是 vector(1024) 类型，
 * 不进实体，只经下方自定义 SQL 原子写入（向量 + 置 published 一条语句完成）。</p>
 */
public interface AiKnowledgeMapper extends BaseMapper<AiKnowledge> {

    /**
     * 发布即向量化：原子写入 embedding 并把状态置 published（C2-F5 硬规则"未向量化不得置 published"）。
     * vec 为 pgvector 文本格式（见 {@code com.dsmarket.common.util.VectorText}），如 {@code [0.1,0.2,...]}。
     *
     * @return 影响行数（0 = id 不存在或已软删）
     */
    @Update("UPDATE ds_ai_knowledge SET embedding = CAST(#{vec} AS vector), " +
            "status = 'published', updated_at = now() " +
            "WHERE id = #{id} AND deleted = 0")
    int publishWithEmbedding(@Param("id") Long id, @Param("vec") String vec);

    /**
     * 批量查哪些 id 已填 embedding（供 VO 的 embeddingFilled 标记；避免逐行 N+1 查询）。
     */
    @Select("<script>" +
            "SELECT id FROM ds_ai_knowledge WHERE deleted = 0 AND embedding IS NOT NULL " +
            "AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Long> selectEmbeddedIds(@Param("ids") Collection<Long> ids);

    /**
     * C2-F1 BM25（全文）检索路：只读 published，按 ts_rank 降序取 top-k，返回顺序即排名。
     *
     * <p>tsvector 表达式与 {@code idx_ai_knowledge_fts} 的 GIN 索引表达式<b>逐字一致</b>，
     * 使 {@code @@} 能命中索引；ts_rank 于 ORDER BY 重算同表达式（推荐词权重默认）。</p>
     *
     * @param q 已 trim 的用户 query（应用层截 ≤200）
     * @param k 召回数（ai.search.topk-bm25）
     * @return 有序命中行（列 id/category/question/answer/faq/status，keyword/embedding 不参与返回）
     */
    @Select("SELECT id, category, question, answer, faq, status FROM ds_ai_knowledge " +
            "WHERE deleted = 0 AND status = 'published' AND " +
            "to_tsvector('zhparser_config', coalesce(question,'') || ' ' || coalesce(answer,'') " +
            "  || ' ' || coalesce(public.ai_keywords_text(keywords),'')) " +
            "@@ plainto_tsquery('zhparser_config', #{q}) " +
            "ORDER BY ts_rank(" +
            "  to_tsvector('zhparser_config', coalesce(question,'') || ' ' || coalesce(answer,'') " +
            "    || ' ' || coalesce(public.ai_keywords_text(keywords),'')), " +
            "  plainto_tsquery('zhparser_config', #{q})) DESC " +
            "LIMIT #{k}")
    List<AiKnowledge> searchBm25(@Param("q") String q, @Param("k") int k);

    /**
     * C2-F2 Dense（向量）检索路：只读 published 且有 embedding 的行，按余弦距离升序取 top-k。
     *
     * <p>{@code embedding <=> ?} 走 {@code idx_ai_knowledge_embedding} HNSW（vector_cosine_ops）。
     * vec 为 pgvector 文本格式（{@code VectorText.of}），与写入端同一 CAST 风格。</p>
     *
     * <p>距离由 ORDER BY 提升为出列（{@code AS dense_distance}，回填实体瞬时字段 denseDistance，
     * 见 {@code AiKnowledge}），供幅度闸 #3 / 评估使用；BM25 路不受影响（该列仅在 Dense 路 SELECT）。</p>
     *
     * @param vec 查询问句向量化后的文本
     * @param k   召回数（ai.search.topk-dense）
     * @return 有序命中行（列 + dense_distance 余弦距离）
     */
    @Select("SELECT id, category, question, answer, faq, status, " +
            "embedding <=> CAST(#{vec} AS vector) AS dense_distance FROM ds_ai_knowledge " +
            "WHERE deleted = 0 AND status = 'published' AND embedding IS NOT NULL " +
            "ORDER BY embedding <=> CAST(#{vec} AS vector) ASC " +
            "LIMIT #{k}")
    List<AiKnowledge> searchDense(@Param("vec") String vec, @Param("k") int k);

    /**
     * C1 FAQ 兜底（主 REQ §2.3）：只读 published 且 {@code faq=TRUE} 的精选条目，
     * 按 ts_rank 降序取 top-k。
     *
     * <p><b>不依赖 embedding</b>（纯 BM25，模型上游故障也能兜底）；tsvector 表达式与
     * {@code idx_ai_knowledge_fts} 逐字一致以命中 GIN 索引。检索列约定与 {@link #searchBm25} 相同。</p>
     *
     * @param q 已 trim 的用户问句（防御截 ≤200 由调用方负责）
     * @param k 回数（FAQ 兜底取 1）
     * @return 有序命中行（首条即顶命 FAQ）
     */
    @Select("SELECT id, category, question, answer, faq, status FROM ds_ai_knowledge " +
            "WHERE deleted = 0 AND status = 'published' AND faq = TRUE AND " +
            "to_tsvector('zhparser_config', coalesce(question,'') || ' ' || coalesce(answer,'') " +
            "  || ' ' || coalesce(public.ai_keywords_text(keywords),'')) " +
            "@@ plainto_tsquery('zhparser_config', #{q}) " +
            "ORDER BY ts_rank(" +
            "  to_tsvector('zhparser_config', coalesce(question,'') || ' ' || coalesce(answer,'') " +
            "    || ' ' || coalesce(public.ai_keywords_text(keywords),'')), " +
            "  plainto_tsquery('zhparser_config', #{q})) DESC " +
            "LIMIT #{k}")
    List<AiKnowledge> searchFaqTop(@Param("q") String q, @Param("k") int k);
}
