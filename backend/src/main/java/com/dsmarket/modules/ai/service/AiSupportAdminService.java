package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.dto.AdminSupportBoardVO;
import com.dsmarket.modules.ai.dto.AdminSupportMessageVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionDetailVO;

/**
 * 坐席工作台服务（C4 切片 2：§4.2 接入 / §4.3 降级 / §4.5 工作台）。
 *
 * <p><b>同样不经 LLM</b>：坐席看到的一切（消息、AI 对话回放）都是读取既有数据 ——
 * AI 对话回放是 Redis 原文直读（§4.5 L 决议），不是让模型"总结一下"。实现类的依赖里
 * 没有 ChatModel / EmbeddingClient，测试用结构断言锁住这一点。</p>
 *
 * <p><b>权限不在本层</b>：谁能调用由 {@code /api/v1/admin/**} → hasRole("ADMIN") 在
 * SecurityConfig 里把住（H10 普通用户 403）。服务层不重复判角色，但也不接受"操作人是买家"的调用
 * —— 本接口的所有方法都只被 admin 控制器调用。</p>
 */
public interface AiSupportAdminService {

    /**
     * 工作台三列表快照（§4.5）：排队中 / 进行中 / 留言，各按最近消息时间倒序，带未读红点。
     * 首次渲染与每次 SSE 重连都先拉它（§4.8 原则①：先快照、后增量）。
     */
    AdminSupportBoardVO board();

    /**
     * 会话详情（§4.5）：完整消息（含接入前缓冲）+ AI 对话回放（L 决议）+ 脱敏用户标识。
     *
     * @throws com.dsmarket.common.exception.BusinessException 404 会话不存在
     */
    AdminSupportSessionDetailVO detail(Long sessionId);

    /**
     * 坐席接入（§4.2）：pending_human → human_active。
     *
     * <p>并发双坐席接入同一会话 → <b>乐观锁仅一成功</b>，落败方 409（H8）。
     * 成功后按 §4.5 把接入前缓冲的买家消息批量置已读（坐席接入动作本身即"看过了"）。
     *
     * @throws com.dsmarket.common.exception.BusinessException 404 不存在 / 409 已被接入或已不在排队中
     */
    void take(Long sessionId);

    /**
     * 标记该会话买家消息已读（§4.5 决议 J"打开即已读"）。
     *
     * <p><b>不回推买家</b>：无 human_read 事件，买家无从知道坐席是否已读。返回清掉的红点数。
     *
     * @throws com.dsmarket.common.exception.BusinessException 404 不存在
     */
    int read(Long sessionId);

    /**
     * 坐席回复（§4.5 回复 / §4.4 坐席 → 买家）：落库 {@code sender=AGENT} 并推送到买家。
     *
     * <p><b>回复不自动关会话</b>（P2 决议，H25）：message_left 的留言被回复后<b>保持</b> message_left，
     * 买家可继续追加；只有坐席显式 {@link #close(Long)} 才进终态。</p>
     *
     * @throws com.dsmarket.common.exception.BusinessException 404 不存在 / 400 内容非法 /
     *         409 会话已结束或尚未接入
     */
    AdminSupportMessageVO reply(Long sessionId, String content);

    /**
     * 坐席结束会话（§4.5 结束）：PH 任一态 → closed，向买家推 {@code human_close}。
     *
     * @throws com.dsmarket.common.exception.BusinessException 404 不存在 / 409 已是终态
     */
    void close(Long sessionId);

    /**
     * 坐席心跳（§4.2）：刷新在线键（TTL 45s）。工作台每 15s 调一次。
     *
     * @return {@code true} = 本次由离线转在线（首次心跳），admin 流据此推 {@code seat_status(online, login)}
     */
    boolean heartbeat(Long adminId);

    /** 坐席显式登出（§4.2 在线退出③）：清在线键并推 {@code seat_status(offline, logout)} */
    void logout(Long adminId);

    /**
     * 等待超时降级扫描（§4.3）：对"坐席在线但 30s 无人接入"的 pending_human 会话落一条 SYSTEM 提示。
     *
     * <p>由定时任务周期性调用；会话保持 pending_human（不自动转留言，H28），
     * 提示落库使买家重开抽屉时能回读看到（可达性靠回读兜底，不依赖 SSE）。
     *
     * @return 本次新提示的会话数
     */
    int sweepWaitTimeout();
}
