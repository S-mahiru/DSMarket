package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台侧的消息视图（C4 §4.5 会话详情）。
 *
 * <p>与买家侧的 {@link AiSupportMessageVO} <b>是两个类型而不是一个</b>：本类型带
 * {@code readByAgent}（工作台红点要用），买家侧的不带（J 决议：已读回执不外露）。
 * 类型分开后，"把已读状态漏给买家"需要先改类型签名 —— 比共用一个 VO 再靠自觉不序列化要可靠。</p>
 */
@Data
public class AdminSupportMessageVO {

    private Long messageId;

    /** USER / AGENT / SYSTEM */
    private String sender;

    private String content;

    private LocalDateTime createdAt;

    /** 仅对 sender=USER 有意义（AGENT/SYSTEM 恒 true，不参与红点） */
    private Boolean readByAgent;
}
