package com.dsmarket.modules.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果（喂回模型的可读文本）。
 *
 * <p>侧信道约定：越权/不存在等不向调用方区分细节——ok=false 的 error 仅供审计，
 * 对外统一折叠为拒绝/兜底话术（REQ §4.2 同侧信道防护）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    private boolean ok;
    /** ok=true 时给模型的正文（可读文本） */
    private String content;
    /** ok=false 时的内部原因（审计/日志用，不回显给用户） */
    private String error;
}
