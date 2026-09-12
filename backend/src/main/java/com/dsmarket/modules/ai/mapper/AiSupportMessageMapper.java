package com.dsmarket.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.ai.dto.SupportUnreadCount;
import com.dsmarket.modules.ai.entity.AiSupportMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 人工消息表 Mapper（C4）。
 *
 * <p>切片 1：按会话正序取全量（{@code GET /support/session} 是历史唯一来源，SSE 不重放历史）。
 * 切片 2：未读批量置位、跨会话未读计数、SYSTEM 提示的存在性判定。</p>
 */
public interface AiSupportMessageMapper extends BaseMapper<AiSupportMessage> {

    /**
     * 按会话取全部消息，{@code id ASC} 正序（= created_at 顺序）。
     * 坐席接入时要靠它回放 pending_human 期间的缓冲消息（REQ §5 硬规则"接入前消息不丢"），
     * 买家重开抽屉也靠它恢复历史（H20/H21）。
     */
    @Select("SELECT * FROM ds_ai_support_message " +
            "WHERE deleted = 0 AND session_id = #{sessionId} ORDER BY id ASC")
    List<AiSupportMessage> selectBySessionAsc(@Param("sessionId") Long sessionId);

    // ------------------------------------------------------------ 切片 2：工作台

    /**
     * 批量已读（§4.5 决议 J"打开即已读"）：把该会话中买家发出且未读的消息全部置 true。
     *
     * <p>只动 {@code sender='USER'} 的行：AGENT/SYSTEM 消息不参与红点，把它们也"置已读"
     * 会让字段语义从"坐席是否看过买家的话"退化成"这行被扫过"，红点口径就糊了。</p>
     *
     * <p>本操作<b>不产生任何推送</b>——已读回执不回推买家（§4.5 决议，无 human_read 事件）。</p>
     *
     * @return 本次被置位的条数（= 清掉的红点数）
     */
    @Update("UPDATE ds_ai_support_message SET read_by_agent = TRUE, updated_at = now() " +
            "WHERE session_id = #{sessionId} AND sender = 'USER' " +
            "AND read_by_agent = FALSE AND deleted = 0")
    int markUserMessagesRead(@Param("sessionId") Long sessionId);

    /**
     * 跨会话未读计数（§4.5 工作台红点）：一次查回给定会话各自的未读条数，避免 N+1。
     *
     * <p>调用方须保证 {@code sessionIds} 非空——空集合会拼出 {@code IN ()} 非法 SQL，
     * 服务层已提前短路。</p>
     */
    @Select("<script>SELECT session_id AS sessionId, COUNT(*) AS cnt FROM ds_ai_support_message " +
            "WHERE deleted = 0 AND sender = 'USER' AND read_by_agent = FALSE AND session_id IN " +
            "<foreach item='s' collection='sessionIds' open='(' separator=',' close=')'>#{s}</foreach> " +
            "GROUP BY session_id</script>")
    List<SupportUnreadCount> countUnreadBySessions(@Param("sessionIds") Collection<Long> sessionIds);

    /**
     * 该会话是否已存在某条 SYSTEM 提示（§4.3 等待超时提示的"只提示一次"判定）。
     *
     * <p>按<b>固定文案常量</b>判定而不是"是否存在任意 SYSTEM 消息"：接入提示（§4.2）也是 SYSTEM 消息，
     * 用"任意 SYSTEM"会把刚接入的会话误判成已提示过。文案是常量，判定因此是精确的。</p>
     *
     * <p>代价是文案一旦改动，历史会话会被视为"未提示"而补发一次 —— 属可接受的降级
     * （多一条提示，不会丢语义）。若将来 SYSTEM 消息来源变多到文案不再唯一，应改为专用标记列
     * （V9 迁移），届时本方法连同调用点一起替换。</p>
     */
    @Select("SELECT COUNT(*) FROM ds_ai_support_message " +
            "WHERE deleted = 0 AND session_id = #{sessionId} " +
            "AND sender = 'SYSTEM' AND content = #{content}")
    int countSystemMessage(@Param("sessionId") Long sessionId, @Param("content") String content);
}
