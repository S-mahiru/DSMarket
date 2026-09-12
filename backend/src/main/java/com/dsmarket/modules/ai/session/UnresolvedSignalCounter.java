package com.dsmarket.modules.ai.session;

/**
 * "本会话未解决信号"计数（REQ C4 §4.6 F6 触发③）。
 *
 * <p>触发条件原文是"本<b>会话</b>累计未解决信号（低置信或 DISLIKE）≥2 次"，阈值可调
 * （{@code ai.support.unresolved-threshold}）。达阈值后由 {@code AiChatStreamService} 在
 * <b>本轮答复之后、{@code done} 之前</b>下发一条 {@code suggest("UNRESOLVED")} 气泡；
 * 会话仍 {@code ai_active}，<b>点后才转</b>（H5）。</p>
 *
 * <p><b>为什么不是数 {@code ds_ai_issue_pool}</b>——三点，任一条已足够否决：</p>
 * <ul>
 *   <li>该表<b>没有 session_id</b>：只能按 user 数，数不出"本会话"；</li>
 *   <li>它的去重口径是"同 user+question 停止采集"：买家把同一个没解决的问题<b>再问一遍</b>
 *       （恰恰是最强的未解决信号）反而不计入；</li>
 *   <li>行一旦 {@code adopted}/{@code ignored} 就<b>停采</b>：飞轮越成功，数得越少，
 *       与"未解决程度"负相关。</li>
 * </ul>
 * <p>故另起一个纯计数键。两者口径不同，<b>故意不联动</b>。</p>
 *
 * <p>实现须 fail-open：Redis 不可用时 {@link #count} 返回 0（至多少发一次气泡），
 * 加减不抛异常（绝不因为一个提示气泡影响对话主链路）。</p>
 *
 * @see RedisUnresolvedSignalCounter
 */
public interface UnresolvedSignalCounter {

    /** 记一次未解决信号，返回自增后的计数（Redis 故障返回 -1，调用方不应依赖该值做判定） */
    int increment(Long userId);

    /** 当前累计次数（无键 / Redis 故障 → 0） */
    int count(Long userId);

    /** 转人工时清零：已经交给人了，不该继续算在 AI 的账上 */
    void clear(Long userId);
}
