package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * 转人工发起结果（C4 §4.1 统一动作步骤 4 的返回体）。
 *
 * <p>两个分支：坐席在线 → {@code {status:pending_human, tip:"正在为您接入人工客服…"}}；
 * 无人在线 → {@code {status:message_left, tip:"当前暂无客服在线，请留言，我们会尽快回复",
 * promptLeave:true}}。<b>tip 由服务端下发</b>，前端不得自行编造"正在接入"之类话术
 * —— 措辞就是"不谎报有人接"这条底线的落点。</p>
 *
 * <p>{@code promptLeave=true} 是给前端的显式指令：立刻展开留言框。切片 1 恒为 true（无坐席基建）。</p>
 */
@Data
public class SupportRequestResult {

    /** pending_human（有人在线，等待接入）/ message_left（无人在线，直接引导留言） */
    private String status;

    /** 服务端下发提示语（前端原样展示） */
    private String tip;

    /** 是否引导买家立刻留言 */
    private boolean promptLeave;

    /** 会话 id（幂等复用已有会话时返回原 id） */
    private Long sessionId;
}
