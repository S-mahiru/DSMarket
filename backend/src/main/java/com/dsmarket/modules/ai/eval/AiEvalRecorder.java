package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 留痕门面（REQ-20260908-C5 §2 AI 轮日志的组装与落盘）。
 *
 * <p><b>职责边界</b>：本类只做"累加器 → 一行 JSON"的翻译与容错；<b>不决定</b>什么时候记什么
 * （那是 C1/C4 各埋点的事，§5 埋点位置映射表），也<b>不做</b>指标计算（§3/§4 是离线重放脚本的事）。
 * 这条边界是为了让"留痕"与"评估"解耦：改指标口径不用碰线上代码，加埋点不用碰重放脚本。</p>
 *
 * <p><b>不得改变既有行为（§1 硬规则）</b>：本类所有方法对主流程都是"要么成功、要么静默失败"——
 * {@link #startRound} 只分配对象，{@link #finishRound} 全程 try/catch，
 * 连 {@code enabled=false} 也只是"不落盘"而不是"不走这条路"，所以关开关与开开关的差别
 * 被限制在<b>有没有写文件</b>这一件事上（§6.5 验收项就是断言这一点）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiEvalRecorder {

    /**
     * §2 intent 的取值口径（<b>实施取值</b>，REQ 原文是 {@code ORDER_QUERY/PRODUCT_QUERY/
     * KNOWLEDGE_QA/SMALLTALK/…}，末尾省略号说明集合是开放的）。
     *
     * <p><b>关键取舍：不新增意图分类器。</b>给每一轮加一次 LLM 分类调用会改变时延与成本，
     * 直接违反 §1"留痕不得改变对话语义"和 §3"首包时延"的可比性（对照组裸 LLM 没有这一步）。
     * 改用<b>实际工具序列反推</b>：这是本系统里唯一客观、零成本、可复现的意图信号。
     * 代价是分辨力弱于真分类器（例：一句闲聊与一句直答都归 {@link #INTENT_DIRECT_ANSWER}），
     * 论文里须按"派生口径"说明，不得声称是独立分类结果。</p>
     */
    public static final String INTENT_ORDER_QUERY = "ORDER_QUERY";
    public static final String INTENT_KNOWLEDGE_QA = "KNOWLEDGE_QA";
    /** 未走任何工具的直答（含闲聊、以及模型直接凭上下文作答）——实施取值 */
    public static final String INTENT_DIRECT_ANSWER = "DIRECT_ANSWER";
    /** 走了工具但不在已知映射里（未来新增工具时的兜底，避免静默归类错误） */
    public static final String INTENT_OTHER = "OTHER";

    /** 工具名 → intent。首轮执行的工具决定本轮意图（见 {@link #intentOf}） */
    private static final Map<String, String> TOOL_INTENT = Map.of(
            "query_my_order", INTENT_ORDER_QUERY,
            "search_knowledge", INTENT_KNOWLEDGE_QA);

    private final AiProperties properties;
    private final AiEvalSink sink;

    /**
     * 留痕专用序列化器，<b>刻意不复用 Spring 容器里的 ObjectMapper</b>。
     *
     * <p><b>这条是拿真机换来的</b>：本项目 {@code spring.jackson.default-property-inclusion=non_null}
     * 会让 <b>值为 null 的键整个消失</b>。用容器那个 mapper 时，没发生检索的轮次里
     * {@code retrieval} 键会凭空不见 —— 而 §2 的 13 个键是<b>与重放脚本的契约</b>，
     * 缺一个则 §4.4「留痕完整性验收」直接判该轮异常</b>，更要命的是
     * "本轮没检索"与"日志没记下来"从此分不出来，低置信轮的分析基础被抽掉。</p>
     *
     * <p>单测<b>照不到</b>这个坑：那边注入的是默认 {@code new ObjectMapper()}（包含 null），
     * 与线上容器 bean 的配置不同 —— 是 c5-s1-sanity 真机跑第一轮报出 B4 缺键才发现。
     * 因此这里改成自带 mapper：留痕格式是与离线脚本的契约，不该被 API 层的响应美化设置左右。</p>
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 留痕总开关（§6.5 验收项"关闭日志开关后 AI/转人工全部行为不变"的开关本体） */
    public boolean enabled() {
        return properties.getEval().isEnabled();
    }

    /** §2 userIdHash 的唯一算法入口（切片 2 的人工/反馈事件复用同一口径，否则 join 不上） */
    public String userHash(long userId) {
        return UserHash.of(userId, properties.getEval().getHashSalt());
    }

    /**
     * 开一轮累加器。
     *
     * <p>注意<b>无论开关是否打开都返回真实对象</b>：开关只影响 {@link #finishRound} 落不落盘。
     * 若这里返回 null，调用方就得处处判空，而这些判空分支永远不会被真实请求走到 ——
     * 等于用"关掉开关的代码路径"替换了"生产代码路径"，测试覆盖的就不是线上跑的东西了。</p>
     */
    public RoundTrace startRound(long userId, String content) {
        String trimmed = content == null ? "" : content.trim();
        int max = properties.getEval().getContentMax();
        if (trimmed.length() > max) {
            // §2 content「≤500」：AI 态问句本身有 500 上限，这里是防御性截断（防配置被调大后
            // 单行日志膨胀）。截断只影响留痕，不影响已经发生过的对话。
            trimmed = trimmed.substring(0, max);
        }
        return new RoundTrace(userHash(userId), trimmed, System.currentTimeMillis(),
                properties.getEval().getEventSeqMax(), properties.getEval().getAnswerMax());
    }

    /**
     * 收口落盘 —— 一轮 AI 对话的<b>唯一</b>写入口。
     *
     * <p>重复调用同一 trace 会被静默丢弃（{@link RoundTrace#markEmitted()}）。
     * 这是刻意的：重复收尾说明有两条出口都调了本方法（编码错误），但"留痕工具因为被多调一次
     * 而把主流程炸掉"是更糟的结果，所以选择丢弃 + 由单测把关一轮一条，
     * 而不是在这里抛异常去"保护"一个不该发生的状态。</p>
     */
    public void finishRound(RoundTrace trace) {
        if (trace == null || !trace.markEmitted()) {
            return;
        }
        if (!enabled()) {
            return;
        }
        try {
            sink.write(objectMapper.writeValueAsString(toRecord(trace)));
        } catch (Exception e) {
            // §1：写日志失败仅记日志。序列化失败也走同一条兜底（ObjectMapper 配置变化不该影响对话）
            log.warn("[ai][c5] AI 轮留痕组装失败（已忽略）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------ 切片 2：人工/反馈事件（§2 后半）

    /**
     * 事件判别符 —— 与 AI 轮的 {@code "ai_round"} 共用同一份 JSONL，靠本键分流
     * （切片 1 就把这个键留好了，切片 2 只是把取值补齐）。
     */
    public static final String KIND_FEEDBACK = "feedback";
    public static final String KIND_SUPPORT_REQUEST = "support_request";
    public static final String KIND_SEAT = "seat";
    public static final String KIND_MESSAGE = "message";
    /**
     * 人工会话的状态迁移（接入 / 结束）—— <b>§2 未列的补充 kind</b>。
     *
     * <p>§5 埋点表把"C4 request/<b>接入</b>/<b>结束</b>/心跳"一并列进来，但 §2 只给了四个 kind，
     * 而 §3 的"转人工成功率（request→接入收敛）"若没有接入这条事实就<b>无法从日志重算</b>
     * （PG 里有状态，但 §1 要求日志本身可离线重放）。故补一个 kind，不硬塞进 message/seat
     * —— 那会让"消息数"或"在线次数"被状态迁移污染。</p>
     */
    public static final String KIND_SUPPORT_SESSION = "support_session";

    /**
     * 事件主体（<b>§2 未列的补充键</b>）：{@code seat} 事件的 {@code userIdHash} 是<b>坐席</b>
     * （ADMIN）的哈希，其余是买家的。没有这个判别符，离线侧无法区分"某个 hash 发过言"与
     * "某个 hash 上过线"，两者会混进同一张用户维表。
     */
    public static final String ACTOR_BUYER = "buyer";
    public static final String ACTOR_AGENT = "agent";

    /**
     * 人工/反馈事件的<b>唯一写入口</b>（§2 后半）。与 {@link #finishRound} 同属"要么成功、
     * 要么静默失败"：留痕的任何问题都不得反噬 C4 的人工通道（§1 硬规则）。
     *
     * <p><b>为什么是通用 Map 而不是每类事件一个方法</b>：字段名一旦分散到六七个调用点，
     * 改名/加键就会漏改一处，而漏改的症状是"离线脚本某天开始算不出某个指标"——
     * 静默且滞后。故真正的字段约定收在 {@code SupportEvalTracer} 一个类里，
     * 本方法只负责"信封 + 落盘"。</p>
     *
     * @param fields 业务字段；<b>允许 null 值</b>（null 是有信息量的取值，见 D6 的同类取舍），
     *               但不得出现信封保留键 —— 那些键以本方法写入的为准，业务侧覆盖无效
     */
    public void event(String kind, String actor, long userId, Map<String, Object> fields) {
        if (kind == null || !enabled()) {
            return;
        }
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("ts", System.currentTimeMillis());
            m.put("userIdHash", userHash(userId));
            m.put("actor", actor);
            if (fields != null) {
                // putIfAbsent 而非 putAll：信封四键是权威。若用 putAll，某处手滑传个 kind
                // 就能把整行归错类，而重放脚本会照着错类去算 —— 又是一个静默偏差。
                fields.forEach(m::putIfAbsent);
            }
            sink.write(objectMapper.writeValueAsString(m));
        } catch (Exception e) {
            log.warn("[ai][c5] 人工/反馈事件留痕组装失败（已忽略）: {}", e.toString());
        }
    }

    // ------------------------------------------------------------ 组装

    /**
     * 键序刻意与 §2 表格一致：重放脚本/人工看文件时不用来回查文档；
     * 也便于把某一版日志 diff 出"字段是不是少了一个"。
     */
    private Map<String, Object> toRecord(RoundTrace t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "ai_round");
        m.put("ts", System.currentTimeMillis());
        m.put("userIdHash", t.getUserIdHash());
        // §2：「打断/error 则记空并标 roundId」——空串而非 null，保证必含键永远是 string 类型，
        // 重放脚本不必为 null/缺键写两套分支
        m.put("replyId", t.getReplyId() == null ? "" : t.getReplyId());
        m.put("roundId", t.getId());
        m.put("content", t.getContent());
        // §2 未列的补充键 answer（缺口说明见 RoundTrace#getAnswer）。
        // 永远写字符串、绝不写 null：没有内容事件的轮（纯 error / 干净作废）记空串，
        // 这样离线脚本不必为"这一列有时是字符串有时是 None"写两套分支 —— 与 replyId 同一条理由。
        m.put("answer", answerOf(t));
        m.put("intent", intentOf(t.getTools()));
        m.put("tools", t.getTools());
        // §2 把 retrieval 的**类型**定成 object（不是 object?），且 kind 的取值里有 "none" ——
        // 所以"本轮没查知识库"也写成一个对象，而不是 null。这样离线脚本不必为"这一列有时是字典
        // 有时是 None"写两套分支，表格也不会因为类型混用把空值算成缺失。
        // 区分口径：conf=null ⇒ 本轮压根没调检索工具；conf="low"/"high" ⇒ 调了，只是可能没召回。
        m.put("retrieval", t.getRetrieval() == null ? noRetrieval() : t.getRetrieval());
        m.put("replyKind", t.resolveReplyKind().value());
        m.put("suggestSent", t.getSuggestReason() != null);
        m.put("eventSeq", eventSeqOf(t));
        // §2「firstDeltaMs | long」：没有内容事件（纯 error / 干净作废）时记 -1，
        // 用 0 会和"瞬时返回"混淆 —— 均值口径上 0 是个会拉低首包时延的假数据
        m.put("firstDeltaMs", t.getFirstContentMs() == null ? -1L : t.getFirstContentMs());
        // §2 outcome「标注回填，见 §3」：线上永远写空对象，由 §4.2 的标注 CSV 在离线 join 回来。
        // 留痕文件是 append-only 的事实记录，绝不为了写标注去改历史行。
        m.put("outcome", new LinkedHashMap<String, Object>());
        if (t.getSuggestReason() != null) {
            m.put("suggestReason", t.getSuggestReason());
        }
        if (t.getErrorCode() != null) {
            m.put("errorCode", t.getErrorCode());
        }
        if (t.getInterruptCause() != null) {
            m.put("interruptCause", t.getInterruptCause());
        }
        return m;
    }

    /**
     * "本轮没有发生检索"的 §2 形态。
     *
     * <p>用 {@code LinkedHashMap} 而不是 {@code Map.of()}：后者<b>不接受 null 值</b>，
     * 而这里三个字段的 null 恰恰是有信息量的（conf=null 是 §2 明列的取值，语义是"这一档不适用"）。</p>
     */
    private Map<String, Object> noRetrieval() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", "none");
        e.put("topScore", null);
        e.put("topCategory", null);
        e.put("conf", null);
        return e;
    }

    /**
     * §2 intent：<b>首个实际执行的工具</b>决定意图。
     *
     * <p>为什么取首个而不是"最具体的那个"：工具序列是模型自己排的，首个是它对本轮的第一判断；
     * 用"取优先级最高的"会引入一张本不存在的优先级表，让口径变得不可解释且无法从日志复算。</p>
     */
    private String intentOf(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return INTENT_DIRECT_ANSWER;
        }
        Object first = tools.get(0).get("tool");
        return TOOL_INTENT.getOrDefault(String.valueOf(first), INTENT_OTHER);
    }

    /** §2 eventSeq：{@code tool_begin,d,d,d,done} 形态；超长时尾部记 {@code …+N} */
    /**
     * 答复原文的落痕形态。截断<b>必须可见</b>：标注者拿到一段戛然而止的答复，
     * 会把"日志截断了"读成"模型只答了一半"，凭空压低答对率。
     * 标记样式与 {@code eventSeq} 的 {@code …+N} 同族，便于人眼一眼认出是留痕侧所为。
     */
    private String answerOf(RoundTrace t) {
        String a = t.getAnswer();
        if (t.getAnswerOverflow() > 0) {
            return a + "…[留痕截断,另有 " + t.getAnswerOverflow() + " 字符未记录]";
        }
        return a;
    }

    private String eventSeqOf(RoundTrace t) {
        String seq = String.join(",", t.getEventSeq());
        if (t.getEventSeqOverflow() > 0) {
            seq = seq.isEmpty() ? "…+" + t.getEventSeqOverflow()
                    : seq + ",…+" + t.getEventSeqOverflow();
        }
        return seq;
    }
}
