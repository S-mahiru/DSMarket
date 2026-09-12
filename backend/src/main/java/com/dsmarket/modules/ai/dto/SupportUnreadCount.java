package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * "某会话有几条买家未读"的聚合投影（C4 §4.5 工作台未读红点）。
 *
 * <p>不是对外的 VO，而是 Mapper 的 {@code GROUP BY} 结果载体：工作台三列表一次查回全部会话的
 * 未读数，避免"每条会话查一次未读"的 N+1。</p>
 */
@Data
public class SupportUnreadCount {

    private Long sessionId;

    /** 该会话中 {@code sender=USER 且 read_by_agent=false} 的条数 */
    private Integer cnt;
}
