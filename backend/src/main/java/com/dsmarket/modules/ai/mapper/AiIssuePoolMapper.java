package com.dsmarket.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.ai.entity.AiIssuePool;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * AI 客服问题池 Mapper（C3）。
 *
 * <p>普通列读写走 MyBatis-Plus {@link BaseMapper}；下方自定义 SQL 承载采集幂等/停采判定
 * （主 REQ §4.4"adopted/ignored 停采" + C3-F1/F2 幂等口径，切片 1）与状态复核迁移
 * （行锁/回填/CAS 采纳忽略/F6 兜底扫描，切片 2）。</p>
 */
public interface AiIssuePoolMapper extends BaseMapper<AiIssuePool> {

    /**
     * 同用户同问句是否已存在（任意状态 pending/adopted/ignored）。
     * 低置信采集幂等：同 user+question 已有记录 → 不重复写（C3-F1）。
     *
     * @return 命中行数
     */
    @Select("SELECT count(*) FROM ds_ai_issue_pool " +
            "WHERE deleted = 0 AND user_id = #{userId} AND question = #{question}")
    int countByUserQuestion(@Param("userId") Long userId, @Param("question") String question);

    /**
     * 该问句是否已被处置（全局 adopted/ignored）。主 REQ §4.4"adopted/ignored 停采"：
     * 已被采纳（知识已存在）或已被判忽略 → 不再采集同一问句。
     *
     * @return 命中行数
     */
    @Select("SELECT count(*) FROM ds_ai_issue_pool " +
            "WHERE deleted = 0 AND question = #{question} " +
            "AND status IN ('adopted', 'ignored')")
    int countAdoptedOrIgnoredByQuestion(@Param("question") String question);

    /**
     * 同用户 + 同 replyId + **同问句** 是否已有点踩记录（source=DISLIKE 幂等，C3-F2）。
     * 并发兜底由唯一索引 {@code uk_ai_issue_pool_dislike_reply_question} 保证。
     *
     * <p><b>为什么必须带 question 维度（#127）</b>：{@code replyId} 是**会话内**从 "1" 递增的轮次号
     * （{@code RoundWindow.nextReplyId}，Redis TTL 30min），会话过期重建后会**回收**；而本查询
     * **不带 status 条件**（含已 adopted/ignored）。若只按 {@code (user_id, reply_id)} 判重，则
     * 买家一旦在任意会话第 N 轮点过踩，此后**所有**会话第 N 轮的点踩都会被永久判为重复而静默丢弃
     * —— 漏采（非脏数据），并连带 {@code AiController.feedback} 不累加未解决计数。
     * 带上问句后只有三种情形：同会话同轮连点（同问句）→ 仍去重；跨会话**不同**问句撞号 → 不再误挡；
     * 跨会话同问句撞号 → 仍去重（同一问题一条池记录，与 {@link #countByUserQuestion} 口径一致）。
     * 真机 A-B 复现见 {@code docs/ai-experiments/127-replyid-recycle/}。</p>
     *
     * <p>用 {@code md5(question)} 而非原文：与部分唯一索引的表达式一致，且规避 B-tree 条目上限
     * （question 是 TEXT，500 字全 4 字节字符即 2000 字节，余量不足）。</p>
     *
     * @return 命中行数
     */
    @Select("SELECT count(*) FROM ds_ai_issue_pool " +
            "WHERE deleted = 0 AND source = 'DISLIKE' AND user_id = #{userId} " +
            "AND reply_id = #{replyId} AND md5(question) = md5(#{question})")
    int countByUserReplyAndQuestion(@Param("userId") Long userId, @Param("replyId") String replyId,
                                    @Param("question") String question);

    // ------------------------------------------------------------ 切片 2：状态复核 + F6 兜底

    /**
     * 采纳前置行锁（C3-F4）：{@code SELECT ... FOR UPDATE} 串行化并发双采纳。
     * 事务内第二次采纳者锁内复查见 adopted → 幂等返回，不再写知识。
     *
     * @return 该行（含全列）；不存在/已软删 → null
     */
    @Select("SELECT * FROM ds_ai_issue_pool WHERE deleted = 0 AND id = #{id} FOR UPDATE")
    AiIssuePool lockById(@Param("id") Long id);

    /**
     * 回填关联知识 id（实施：采纳先建知识 draft 即回填，供 F6 扫描定位待向量化行）。
     */
    @Update("UPDATE ds_ai_issue_pool SET related_knowledge_id = #{kid}, updated_at = now() " +
            "WHERE id = #{id} AND deleted = 0")
    int linkKnowledge(@Param("id") Long id, @Param("kid") Long kid);

    /**
     * 采纳迁移：pending → adopted（CAS：仅当仍 pending；避免覆盖已忽略/已软删）。
     * 采纳同步向量化成功路径与 F6 兜底成功路径共用。
     *
     * @return 影响行数（0 = 已非 pending，并发已被处理）
     */
    @Update("UPDATE ds_ai_issue_pool SET status = 'adopted', updated_at = now() " +
            "WHERE id = #{id} AND status = 'pending' AND deleted = 0")
    int casAdopt(@Param("id") Long id);

    /**
     * 忽略迁移：pending → ignored（CAS；adopted/ignored 已处置 → 0 行，服务层按幂等处理）。
     */
    @Update("UPDATE ds_ai_issue_pool SET status = 'ignored', updated_at = now() " +
            "WHERE id = #{id} AND status = 'pending' AND deleted = 0")
    int casIgnore(@Param("id") Long id);

    /** 向量化兜底失败计数 +1（F6；超上限由服务层告警）。 */
    @Update("UPDATE ds_ai_issue_pool SET retry_count = retry_count + 1, updated_at = now() " +
            "WHERE id = #{id} AND deleted = 0")
    int incrementRetry(@Param("id") Long id);

    /**
     * F6 兜底扫描：pending 且已关联知识（adopt 先建即回填）且关联知识仍 draft、未向量化 的行。
     * JOIN 到 ds_ai_knowledge 用 embedding IS NULL 定位"建了知识但向量化没成功"的采纳，
     * 每分钟一次，≤limit 条避免单轮刷太多。
     */
    @Select("SELECT ip.* FROM ds_ai_issue_pool ip " +
            "JOIN ds_ai_knowledge k ON k.id = ip.related_knowledge_id AND k.deleted = 0 " +
            "WHERE ip.deleted = 0 AND ip.status = 'pending' AND ip.related_knowledge_id IS NOT NULL " +
            "AND k.status = 'draft' AND k.embedding IS NULL " +
            "ORDER BY ip.id ASC LIMIT #{limit}")
    List<AiIssuePool> selectRetryableIssues(@Param("limit") int limit);
}
