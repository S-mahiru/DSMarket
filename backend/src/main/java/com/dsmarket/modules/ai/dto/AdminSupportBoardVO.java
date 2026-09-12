package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.util.List;

/**
 * 工作台三列表快照（C4 §4.5）：排队中 / 进行中 / 留言。
 *
 * <p>每列表均按 {@code last_msg_at} 倒序（无消息则按发起时间）。
 * <b>快照是初始渲染的权威</b>：SSE 只推此后增量、不重放历史，因此每次重连都必须先拉本快照
 * 再收增量（§4.8 断线重连原则①）。</p>
 */
@Data
public class AdminSupportBoardVO {

    /** 排队中：status=pending_human */
    private List<AdminSupportSessionVO> queued;

    /** 进行中：status=human_active */
    private List<AdminSupportSessionVO> active;

    /** 留言：status=message_left */
    private List<AdminSupportSessionVO> left;
}
