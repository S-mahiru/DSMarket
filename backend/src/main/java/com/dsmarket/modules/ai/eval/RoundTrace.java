package com.dsmarket.modules.ai.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一轮 AI 对话的留痕累加器（REQ-20260908-C5 §2「AI 轮日志」）。
 *
 * <p><b>为什么是可变累加器而不是在各出口各写一条</b>：C1 一轮有 10 个出口（正常 done、FAQ 兜底、
 * 上游故障兜底、超时 error、超轮 error、异常 error、客户端断开、转人工打断×3）。在出口处各写一条
 * 日志，迟早会漏一个、或某条路径写两条 —— 而"漏一条"在指标口径上是<b>静默偏差</b>，看不出来。
 * 改成"累加 + 单一出口落盘"（{@code AiChatStreamService.run} 的 finally）后，
 * <b>一轮恰好一条</b>成为结构性保证（{@link #markEmitted()}）。</p>
 *
 * <p><b>隐私</b>：本对象<b>只持有 userIdHash，不持有明文 userId</b>（§2 明令"不用明文 userId"）。
 * 哈希在 {@link AiEvalRecorder#startRound} 里当场上算，明文从不进入本对象。</p>
 *
 * <p>本类是纯数据累加器，不含任何 IO/异常路径 —— 落盘与容错在 {@link AiEvalRecorder}。</p>
 */
public class RoundTrace {

    /** §2 replyKind 五档，取值与 REQ 原文逐字一致（论文口径要对得上文字） */
    public enum ReplyKind {
        /** delta 正常 */
        AI("ai"),
        /** FAQ 兜底（知识低置信短路 → 顶命原文直发） */
        FAQ("faq"),
        /** 降级话术（上游故障兜底） */
        FALLBACK("fallback"),
        /** 被打断：客户端断开 / 转人工竞态作废 */
        INTERRUPTED("interrupted"),
        ERROR("error");

        private final String value;

        ReplyKind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * 打断原因（§2 未列的<b>实施补充键</b>）。
     *
     * <p>不区分就分不清"买家自己按了停止"和"买家转了人工把 AI 打断"—— 两者都是
     * {@code replyKind=interrupted}，但对 P2 的口径意义完全相反：前者是买家主动放弃，
     * 后者恰恰是本模块<b>做对了</b>（人工态绝不混入 AI 答复）。</p>
     */
    public static final String INTERRUPT_CLIENT_ABORT = "client_abort";
    public static final String INTERRUPT_HUMAN_TAKEOVER = "human_takeover";

    /** 标注回填的 join 键（§4.2：导出 CSV → 第二人盲评 → 回填）。见类注释末尾 */
    private final String roundId = UUID.randomUUID().toString();
    private final String userIdHash;
    private final String content;
    private final long startedAtMs;

    private final List<Map<String, Object>> tools = new ArrayList<>();
    private Map<String, Object> retrieval;
    private final List<String> eventSeq = new ArrayList<>();
    private final int eventSeqMax;
    /**
     * 答复原文累加器（§2 未列的<b>实施补充键</b>，见 {@code getAnswer()} 的说明）。
     * 用 StringBuilder 而非 List：落痕要的是<b>拼好的正文</b>，不是 delta 分片序列。
     */
    private final StringBuilder answer = new StringBuilder();
    private final int answerMax;
    private int answerOverflow;

    private ReplyKind replyKind;
    private String interruptCause;
    private String replyId;
    private String suggestReason;
    private String errorCode;
    private Long firstContentMs;
    /** single-emission 守卫：见类注释 */
    private boolean emitted;

    RoundTrace(String userIdHash, String content, long startedAtMs, int eventSeqMax, int answerMax) {
        this.userIdHash = userIdHash;
        this.content = content;
        this.startedAtMs = startedAtMs;
        this.eventSeqMax = eventSeqMax;
        this.answerMax = Math.max(0, answerMax);
    }

    // ------------------------------------------------------------ 写入（服务与装饰器用）

    /** §2 tools 数组的一个元素：{@code {tool, ok, reason?}} */
    public void tool(String name, boolean ok, String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("tool", name);
        e.put("ok", ok);
        if (reason != null) {
            e.put("reason", reason);
        }
        tools.add(e);
    }

    /** §2 retrieval 对象。kind/conf 的口径见 {@code SearchKnowledgeTool.RetrievalTrace} */
    public void retrieval(String kind, double topScore, String category, String conf) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("topScore", topScore);
        e.put("topCategory", category);
        e.put("conf", conf);
        this.retrieval = e;
    }

    /**
     * 中断原因。**同时把 replyKind 定为 interrupted**：打断的两种成因（客户端断开、转人工竞态）
     * 都不产生 done/error，语义上就是 §2 的 {@code interrupted}，两处分开写迟早会漂移。
     */
    public void interrupt(String cause) {
        this.interruptCause = cause;
        this.replyKind = ReplyKind.INTERRUPTED;
    }

    /**
     * 记账一个已下发的事件（§2 eventSeq 的数据源）。
     *
     * <p>超出 {@code eventSeqMax} 后不再记 token，只累加溢出计数，序列化时折成 {@code …+N}。
     * 需要这个上限是因为：问句有 500 字上限，但<b>模型的答复长度没有上限</b>，一句长答复按
     * {@code DeltaChunker.MAX_CHUNK=48} 切能出几十个 delta，不封顶单行日志会长得没法读。</p>
     */
    public void noteEvent(String token) {
        if (eventSeq.size() < eventSeqMax) {
            eventSeq.add(token);
        } else {
            eventSeqOverflow++;
        }
    }

    /**
     * 累加答复正文（{@code delta} 的文本、或兜底轮的 {@code fallback} 正文）。
     *
     * <p><b>为什么超限后是继续计数而不是丢弃</b>：截断本身没关系（标注看的是主体），
     * 但<b>"被截过"必须留在日志里</b> —— 否则标注者拿到一段戛然而止的答复，会把
     * "日志截断了"误判成"模型答了一半"，直接压低答对率。故溢出部分记数，
     * 由 {@link AiEvalRecorder} 在序列化时写成可见标记。</p>
     */
    public void appendAnswer(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int room = Math.max(answerMax - answer.length(), 0);
        // 先算出**实际留下的前缀**，再拿它算溢出。
        // 若拿"预算 room"去算，代理对被让出的那个字符就成了"既没留下、也没计入溢出"的幽灵
        // —— 日志上的截断字数会比真实少，而这正是标注者唯一能看见的线索。
        String keep = safePrefix(text, room);
        answer.append(keep);
        answerOverflow += text.length() - keep.length();
    }

    /**
     * 按 UTF-16 长度截前缀，且<b>不切断代理对</b>。
     *
     * <p>截在低位字符前会留下一个孤立的高位代理，序列化成 JSON 后再读出来就是 U+FFFD —
     * 与前端 {@code TextDecoder} 必须带 {@code {stream:true}} 是同一类坑：截断发生在
     * "字符"与"码点"不是一回事的地方。emoji 与部分生僻字会真的踩到。</p>
     */
    private static String safePrefix(String s, int room) {
        int end = Math.min(room, s.length());
        if (end > 0 && end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    /** §2 firstDeltaMs：首个"内容事件"（delta 或 fallback）距本轮开始的毫秒数 */
    public void markFirstContent(long nowMs) {
        if (firstContentMs == null) {
            this.firstContentMs = Math.max(0L, nowMs - startedAtMs);
        }
    }

    public void replyKind(ReplyKind kind) {
        this.replyKind = kind;
    }

    public void replyId(String id) {
        this.replyId = id;
    }

    public void suggest(String reason) {
        this.suggestReason = reason;
    }

    public void error(String code) {
        this.errorCode = code;
    }

    private int eventSeqOverflow;

    // ------------------------------------------------------------ 读取（AiEvalRecorder 用）

    public String getId() {
        return roundId;
    }

    public String getUserIdHash() {
        return userIdHash;
    }

    public String getContent() {
        return content;
    }

    /**
     * 答复原文（§2 未列的<b>实施补充键</b>）。
     *
     * <p><b>为什么必须有这个键</b>：§3 的"端到端标注答对率"是 P2-H1 的<b>主证据</b>，
     * 口径是"抽样 → 第二人盲评 → correct/rated"。可 §2 的必含键里只有 <b>问句</b>，
     * 没有<b>答了什么</b> —— 只给标注者一个问句，他无从判断答得对不对。
     * 答复在系统里只活两处：SSE 流（发完即散）与 Redis 会话（{@code ai.session.ttl} 30 分钟）。
     * 30 分钟后无从取证，而人工标注必然是滞后几小时到几天的。故这是 §2 的<b>缺口</b>，
     * 不是可选增强：不补它，§3 那一行主证据就永远算不出来。</p>
     */
    public String getAnswer() {
        return answer.toString();
    }

    /** 超出 {@code ai.eval.answer-max} 未留痕的字符数（0 = 完整） */
    public int getAnswerOverflow() {
        return answerOverflow;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public List<Map<String, Object>> getTools() {
        return tools;
    }

    public Map<String, Object> getRetrieval() {
        return retrieval;
    }

    public List<String> getEventSeq() {
        return eventSeq;
    }

    public int getEventSeqOverflow() {
        return eventSeqOverflow;
    }

    public String getReplyId() {
        return replyId;
    }

    public String getSuggestReason() {
        return suggestReason;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Long getFirstContentMs() {
        return firstContentMs;
    }

    public String getInterruptCause() {
        return interruptCause;
    }

    /**
     * 收尾判定：显式设过就用它，没设过就是 {@code interrupted}。
     *
     * <p>为什么默认值选 interrupted 而不是 error/ai：走到"没显式设过"的出口只剩三类 ——
     * 客户端断开、转人工竞态作废、以及 delta 发出后被打断。它们共同点就是<b>本轮没有正常结束</b>，
     * 正是 §2 的 interrupted。若默认成 ai，一条被作废的轮会被算进答对率分母。</p>
     */
    public ReplyKind resolveReplyKind() {
        return replyKind == null ? ReplyKind.INTERRUPTED : replyKind;
    }

    /**
     * 单次落盘守卫：返回 true 表示"这次归你写"，false 表示已经写过了。
     *
     * <p>调用方（{@link AiEvalRecorder#finishRound}）据此丢弃重复收尾。重复收尾本身是 bug
     * （说明有两条出口都调了 finishRound），但留痕工具不该让主流程因此抛异常，所以选择
     * "静默丢弃 + 调用方日志"，由单测把关"一轮恰好一条"。</p>
     */
    boolean markEmitted() {
        if (emitted) {
            return false;
        }
        emitted = true;
        return true;
    }
}
