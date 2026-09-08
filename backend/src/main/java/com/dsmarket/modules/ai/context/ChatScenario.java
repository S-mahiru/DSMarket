package com.dsmarket.modules.ai.context;

import com.dsmarket.modules.ai.dto.ChatRequest;

/**
 * 页面上下文 → 场景注入行（REQ C1 §2/§4.1：拼进 system 提示词，辅助模型理解"用户正在看什么"）。
 *
 * <p>越权/缺失规则：orderNo 不属于当前用户、productId 不存在/已下架 → 一律返回空串（忽略字段，
 * 不报错、不注入）。任何场景不得携带他人数据或 userId。DB 异常 → 空串降级（不阻塞对话）。</p>
 */
public interface ChatScenario {

    /** 返回要拼入 system 的场景行；无可注入 → 空串 */
    String resolve(Long userId, ChatRequest.Context context);
}
