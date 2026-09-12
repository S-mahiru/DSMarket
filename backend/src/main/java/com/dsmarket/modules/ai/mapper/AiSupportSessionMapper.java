package com.dsmarket.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.ai.entity.AiSupportSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 人工会话主表 Mapper（C4）。
 *
 * <p>普通列读写走 MyBatis-Plus {@link BaseMapper}；下方自定义 SQL 承载状态机判定与工作台列表。
 * 切片 1：查活跃会话 / 查最新会话 / 缓冲态降级 / 触达；切片 2：三列表查询 / CAS 接入 / CAS 关闭 /
 * 等待超时扫描。</p>
 *
 * <p>约定同 C3：SQL 一律带 {@code deleted = 0}；写操作一律带 {@code updated_at = now()}。
 * 状态迁移（接入/关闭）一律写成 <b>CAS</b>（{@code WHERE status = 期望值}），靠影响行数表达胜负
 * —— 并发双坐席接入靠它定胜负（H8），而不是"先查再改"。</p>
 */
public interface AiSupportSessionMapper extends BaseMapper<AiSupportSession> {

    /**
     * 查该买家的活跃（非终态）会话 —— PH 集合 {pending_human, human_active, message_left}。
     * 供 H7 重复 request 幂等复用、H12 区分"仅存在已 closed 会话"（→ 该新建）。
     *
     * <p>与 V8 部分唯一索引 {@code uk_ai_support_session_active_user} 的 WHERE 子句保持一致；
     * 并发下"查无 → 新建"仍可能撞索引，由服务层捕获冲突后回查兜底。</p>
     *
     * @return 最新一条活跃会话；无 → null
     */
    @Select("SELECT * FROM ds_ai_support_session " +
            "WHERE deleted = 0 AND user_id = #{userId} " +
            "AND status IN ('pending_human', 'human_active', 'message_left') " +
            "ORDER BY id DESC LIMIT 1")
    AiSupportSession selectActiveByUser(@Param("userId") Long userId);

    /**
     * 查该买家最新会话（<b>含 closed</b>）。供发消息/回读时判定"是否存在但已结束"
     * —— closed 要能回 409 SESSION_CLOSED（H18）而不是 NO_ACTIVE_SESSION，
     * 也要能让 {@code GET /session} 回溯终态与历史（H21）。
     *
     * @return 最新一条会话；一条都没有 → null
     */
    @Select("SELECT * FROM ds_ai_support_session " +
            "WHERE deleted = 0 AND user_id = #{userId} ORDER BY id DESC LIMIT 1")
    AiSupportSession selectLatestByUser(@Param("userId") Long userId);

    /**
     * 发消息成功后触达会话：刷新 {@code last_msg_at}（工作台列表按它倒序）。
     *
     * <p>带 {@code status IN PH} 条件是<b>并发安全阀</b>：若此刻坐席已把会话 closed，
     * 更新 0 行 → 服务层据此回滚本次落库并回 409（H18 的并发路径，避免给已结束会话追加孤儿消息）。</p>
     *
     * @return 影响行数（0 = 会话已离开 PH，调用方必须按并发冲突处理）
     */
    @Update("UPDATE ds_ai_support_session SET last_msg_at = now(), updated_at = now() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND status IN ('pending_human', 'human_active', 'message_left')")
    int touchActive(@Param("id") Long id);

    /**
     * 缓冲态降级：pending_human → message_left（H2 决议——"初始无坐席"或"发消息时仍无坐席在线"
     * 都走这里）。CAS 语义：仅当仍 pending_human 才迁移，避免把已 human_active/closed 的会话改坏。
     *
     * @return 影响行数（0 = 已非 pending_human，服务层按幂等视为已完成）
     */
    @Update("UPDATE ds_ai_support_session SET status = 'message_left', updated_at = now() " +
            "WHERE id = #{id} AND status = 'pending_human' AND deleted = 0")
    int casPendingToMessageLeft(@Param("id") Long id);

    // ------------------------------------------------------------ 切片 2：工作台

    /**
     * 工作台列表：按状态集合取会话，{@code last_msg_at} 倒序（无消息的用发起时间兜底）。
     *
     * <p>一次取回 PH 三态的全部会话，由服务层切成"排队中/进行中/留言"三个列表
     * —— 三条 SQL 换一条，也避免三次查询之间会话状态漂移导致同一会话出现在两个列表里。</p>
     */
    @Select("<script>SELECT * FROM ds_ai_support_session " +
            "WHERE deleted = 0 AND status IN " +
            "<foreach item='s' collection='statuses' open='(' separator=',' close=')'>#{s}</foreach> " +
            "ORDER BY COALESCE(last_msg_at, requested_at) DESC, id DESC</script>")
    List<AiSupportSession> selectByStatuses(@Param("statuses") Collection<String> statuses);

    /**
     * 坐席接入（§4.2）：pending_human → human_active，置 {@code active_at}。
     *
     * <p><b>这是 H8"并发双坐席接入仅一成功"的落点</b>：CAS 语义下一次只有一个调用影响 1 行，
     * 另一个得 0 行 → 服务层回 409。不记"接入人"—— REQ §3.1 无坐席列，本模块也不做坐席分配
     * （§1 明确不做），加一个没人读的列只会假装有路由。</p>
     *
     * @return 影响行数（0 = 已被别人接入或状态已变，调用方按冲突处理）
     */
    @Update("UPDATE ds_ai_support_session SET status = 'human_active', active_at = now(), " +
            "updated_at = now() " +
            "WHERE id = #{id} AND status = 'pending_human' AND deleted = 0")
    int casPendingToActive(@Param("id") Long id);

    /**
     * 坐席结束会话（§4.5 结束 / §5 状态机）：PH 任一态 → closed，置 {@code closed_at}。
     *
     * <p>允许从三个非终态进入 closed：排队中的会话坐席可直接结束（例如发现是误点）、
     * 进行中可结束、留言可结束（P2 决议：回复不自动关，显式结束才关）。</p>
     *
     * @return 影响行数（0 = 已是 closed，重复点"结束"按幂等/冲突处理由服务层决定）
     */
    @Update("UPDATE ds_ai_support_session SET status = 'closed', closed_at = now(), updated_at = now() " +
            "WHERE id = #{id} AND deleted = 0 " +
            "AND status IN ('pending_human', 'human_active', 'message_left')")
    int casClose(@Param("id") Long id);

    /**
     * 等待超时扫描（§4.3）：取发起时间早于 {@code cutoff} 且<b>仍停在</b> pending_human 的会话。
     *
     * <p>"仍停在 pending_human"即天然幂等：一旦坐席接入（human_active）或买家留言降级
     * （message_left），该会话就不再是候选，无需额外标记位。</p>
     */
    @Select("SELECT * FROM ds_ai_support_session " +
            "WHERE deleted = 0 AND status = 'pending_human' AND requested_at < #{cutoff} " +
            "ORDER BY id ASC")
    List<AiSupportSession> selectPendingBefore(@Param("cutoff") LocalDateTime cutoff);
}
