package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工/反馈事件留痕（REQ-20260908-C5 §2 后半 + §5 第 3/4 行）。
 *
 * <p><b>为什么要有这一层</b>：§2 后半有五类事件（{@code feedback / support_request /
 * support_session / seat / message}），分布在六七个调用点。若各处直接调
 * {@link AiEvalRecorder#event} 并各自 {@code put} 字段名，字段约定就散落在六份重复代码里
 * ——漏改一处的症状是"离线脚本某天算不出某个指标"，静默且滞后。故字段名在这里<b>各写一次</b>，
 * 调用方只传业务值。</p>
 *
 * <p><b>落点为什么在 support 包而不是 eval 包</b>：本类要读坐席在线态（{@link SupportSeatPresence}），
 * 若放进 eval 就形成 eval ⇄ support 的包环。方向定为 support → eval（单向）。</p>
 *
 * <p><b>不记消息正文（有意）</b>：§2 给 {@code message} 定的键就是 sessionId + sender。
 * 人工对话正文是全链路最敏感的内容，而 §3 的运行型指标（转人工成功率、留言响应率）一条都不需要它；
 * 需要正文的地方（坐席工作台、人工标注）PG 的 {@code ds_ai_support_message} 才是权威。
 * 留痕不复制正文，既少一处 PII 面，也避免"PG 与 JSONL 两份正文不一致时信谁"的问题。</p>
 *
 * <p><b>调用时机</b>：凡在 {@code @Transactional} 方法体内发生的动作，调用方必须用
 * {@link AfterCommit#run} 包住 —— 否则事务回滚后日志里会留下一条<b>没发生过的事实</b>，
 * 直接污染 P2 的运行指标（这与 C4 的 SSE 推送是同一条理由）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportEvalTracer {

    /** §4.8 {@code seat_status} 的 reason 词表（与工作台事件同词，便于两边 join） */
    public static final String SEAT_LOGIN = "login";
    public static final String SEAT_LOGOUT = "logout";

    /** 人工会话状态迁移的动作取值（与 {@code seat} 的 login/logout 区分开，两者不可混读） */
    public static final String SESSION_TAKE = "take";
    public static final String SESSION_CLOSE = "close";

    private final AiEvalRecorder eval;
    private final SupportSeatPresence seatPresence;

    /**
     * 买家反馈（§5 第 3 行，唯一入口 {@code POST /ai/feedback}）。
     *
     * <p>{@code collected} 是本切片新增的字段，刻意记<b>"这次点踩有没有真的进问题池"</b>：
     * 跨会话 replyId 回收会让点踩被幂等判重挡掉（C4 收口核对 §9.3 的待裁决项），
     * 那是一个<b>只存在于日志里才看得见</b>的漏采 —— 靠它才能量化影响面，
     * 而不是等论文写完才发现点踩数对不上。</p>
     *
     * @param replyId   该轮 replyId；缺失记空串（与 AI 轮日志的 {@code replyId} 同口径）
     * @param collected true = 本次点踩新入问题池；false = 被幂等判重挡掉或回查不到。
     *                  <b>点赞恒 false</b>（问题池只收点踩，C3-F2），故分析时必须先按
     *                  {@code satisfied=false} 过滤再读本字段
     */
    public void feedback(long userId, String replyId, boolean satisfied, String reason, boolean collected) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("replyId", replyId == null ? "" : replyId);
        f.put("satisfied", satisfied);
        f.put("reason", reason);
        f.put("collected", collected);
        eval.event(AiEvalRecorder.KIND_FEEDBACK, AiEvalRecorder.ACTOR_BUYER, userId, f);
    }

    /**
     * 发起转人工（§5 第 4 行）。三个入口共用：买家按钮（USER_REQUEST / AI_SUGGEST）、
     * {@code /chat} 锚点自动触发（ANCHOR_HIT）。锚点路径由服务端内部调用，
     * 故 {@code origin} 恒为 ANCHOR_HIT，买家无法伪造（见 {@code AiSupportOrigin.PUBLIC_VALUES}）。
     *
     * <p><b>幂等复用同样记一条</b>（sessionId 相同）：离线侧按<b>去重后的 sessionId 数</b>
     * 当请求数，而不是按行数 —— 否则买家连点几次按钮就会把"转人工成功率"的分母撑大。
     * 行数本身有信息量（复用了多少次），所以不在这里做去重。</p>
     *
     * @param status 转人工后对外的会话状态（{@code message_left} = 此刻无人在线，诚实引导留言）
     */
    public void supportRequest(long userId, String origin, Long sessionId, String status) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("origin", origin);
        f.put("seatOnline", anySeatOnline());
        f.put("sessionId", sessionId);
        f.put("status", status);
        eval.event(AiEvalRecorder.KIND_SUPPORT_REQUEST, AiEvalRecorder.ACTOR_BUYER, userId, f);
    }

    /** 买家消息落库（人工态发消息，含 {@code /chat} 入口被分流进来的正文） */
    public void userMessage(long userId, Long sessionId, Long messageId, String status) {
        message(userId, sessionId, messageId, AiSupportSender.USER_VALUE, AiEvalRecorder.ACTOR_BUYER, status);
    }

    /** 坐席回复落库（{@code POST /admin/ai/support/{id}/message}） */
    public void agentMessage(long userId, Long sessionId, Long messageId, String status) {
        message(userId, sessionId, messageId, AiSupportSender.AGENT_VALUE, AiEvalRecorder.ACTOR_AGENT, status);
    }

    /**
     * 人工会话状态迁移（接入 / 结束）。<b>SYSTEM 消息不记</b>：它们是"接入提示/坐席繁忙"
     * 这类由系统派生的话术，接入与结束已各有一条 {@code support_session}，再记一遍只会让
     * 消息计数虚高（留言响应率 = 坐席回复数 / 留言数，分母不该被系统提示污染）。
     */
    public void sessionTransition(long userId, Long sessionId, String action, String status) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", action);
        f.put("sessionId", sessionId);
        f.put("status", status);
        eval.event(AiEvalRecorder.KIND_SUPPORT_SESSION, AiEvalRecorder.ACTOR_AGENT, userId, f);
    }

    /**
     * 坐席上线 / 登出（§5 第 4 行）。{@code userIdHash} 这里是<b>坐席</b>的哈希
     * （靠 {@code actor=agent} 与买家事件区分）。
     *
     * <p><b>只在"状态真的变了"时记一次</b>：心跳是 15s 一次的续期，每次记一条会让日志被
     * 续期淹没（2 个坐席 = 每分钟 8 行噪音），而坐席在线时长的重算只需要上线/登出两个端点。</p>
     */
    public void seat(long adminId, String action) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("action", action);
        eval.event(AiEvalRecorder.KIND_SEAT, AiEvalRecorder.ACTOR_AGENT, adminId, f);
    }

    // ------------------------------------------------------------ 私有

    private void message(long userId, Long sessionId, Long messageId, String sender,
                         String actor, String sessionStatus) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sender", sender);
        f.put("sessionId", sessionId);
        f.put("messageId", messageId);
        // 写后状态：pending_human→message_left 的降级就发生在"落这条消息"的同一个事务里，
        // 记在消息上才能从日志复原"是哪一条把会话推进了留言态"
        f.put("sessionStatus", sessionStatus);
        eval.event(AiEvalRecorder.KIND_MESSAGE, actor, userId, f);
    }

    /**
     * 坐席在线态的读取（仅留痕用，只读）。
     *
     * <p>读不到时记 {@code null} 而不是 {@code false}：{@code false} 断言"确实没人在线"，
     * 而那是一个我们并没有验证过的事实。留痕里"没测出来"与"测出来是假"必须分得开
     * ——这与 §2 {@code retrieval.conf=null}（"不适用"）的取舍同源。</p>
     */
    private Boolean anySeatOnline() {
        try {
            return seatPresence.anySeatOnline();
        } catch (Exception e) {
            log.warn("[ai][c5] 坐席在线态读取失败，留痕记 null（已忽略）: {}", e.toString());
            return null;
        }
    }
}
