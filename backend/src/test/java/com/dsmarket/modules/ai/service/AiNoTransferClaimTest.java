package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.service.impl.AiSupportSessionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 H11「AI 态含『已转接真人』字样 → 禁止（自动化断言）」。
 *
 * <p>权威条款：主 REQ §4.2 规则 10「AI 不假装人工：AI 态绝不出『已为您转接真人』之类谎报；
 * 转人工是真实状态切换，不是话术」+ 验收标准 15「AI 不谎报：AI 态全程不含『已为您转接真人/人工』
 * 字样（<b>自动化断言</b>）」。验收标准把这个词写死了，所以只能靠断言满足，不能靠"看着像没有"。</p>
 *
 * <p><b>为什么这条是真空缺而不是"已经有了"</b>：三份 C4 DECISION 与四个 harness 里
 * {@code H11} 一次都没出现（H1~H28 中只有 H9/H11/H22 如此），单测里也没有任何一条针对它。
 * 此前散落的"不谎报"注释（如 {@code AiSupportSessionServiceImplTest} 的"不得谎报正在接入"）
 * 断言的是<b>人工通道的 tip</b>，而 H11 管的是 <b>AI 态</b> —— 不是同一条。</p>
 *
 * <p><b>断言范围（有意为之）</b>：只扫<b>服务端自己书写的 AI 态文案</b>。
 * 人工通道的 {@code TIP_PENDING}（"正在为您接入人工客服…"）等<b>不在本表内、也不该在</b>：
 * 它们只在真的建了 pending_human 行之后才下发，说的是真事。把它们并进来会逼实现改掉诚实文案，
 * 属于把断言写反。LLM 自由生成的那部分静态断言不了 —— 那由 {@code SYSTEM_PROMPT} 第 6 条约束，
 * 运行期由 {@code c4-s3-sanity.py} 扫实际回答兜底。</p>
 */
@DisplayName("C4 H11：AI 态不得出现『已转接真人』类谎报（自动化断言）")
class AiNoTransferClaimTest {

    /**
     * 违规词表：只收<b>宣称转接已完成或正在进行</b>的说法。
     *
     * <p>刻意<b>不收</b>「转人工」「人工客服」「接入」等裸词 —— 「建议转人工客服」是正当建议
     * （{@link AiChatStreamService#TOO_MANY_MESSAGE} 就是这么写的），收进来会把好文案判成违规。</p>
     */
    private static final String[] FORBIDDEN_CLAIMS = {
            "已为您转接", "已转接", "正在为您转接", "正在转接",
            "已接入人工", "已为您接入", "人工客服已接入", "人工客服已为您",
            "已为您联系", "已联系人工", "已通知人工", "人工已接入",
    };

    /** 扫一条文本，命中任一违规说法即返回命中的词，否则 null。 */
    private static String findClaim(String text) {
        if (text == null) {
            return null;
        }
        for (String bad : FORBIDDEN_CLAIMS) {
            if (text.contains(bad)) {
                return bad;
            }
        }
        return null;
    }

    /**
     * 防"空断言"：先证明这个扫描器<b>抓得住</b>真的谎报。
     *
     * <p>没有这一段，一个永远返回 null、或词表被误删成空的扫描器，会让下面几条断言全绿 ——
     * 与 dsmarket-error-response-assertions 记的是同一类假 PASS。</p>
     */
    @Test
    @DisplayName("扫描器自检：真谎报必须被抓住（防空断言）")
    void scanner_actuallyCatchesRealClaims() {
        assertTrue(FORBIDDEN_CLAIMS.length > 0, "词表为空会让本类全部断言失效");
        for (String lie : new String[]{
                "已为您转接真人客服，请稍候。",
                "您好，已转接人工客服。",
                "正在为您转接，请稍等。",
                "已通知人工处理您的问题。",
        }) {
            assertTrue(findClaim(lie) != null, "扫描器漏掉了明显的谎报：" + lie);
        }
    }

    /** 反向自检：正当的"建议/引导"不得被误判 —— 否则实现会被迫删掉好文案。 */
    @Test
    @DisplayName("扫描器自检：建议与引导类措辞不得误伤")
    void scanner_doesNotFlagLegitimateSuggestions() {
        for (String ok : new String[]{
                "这个问题我暂时处理不了，建议转人工客服。",
                "您可以点击『转人工』入口联系人工客服。",
                "如需人工客服，请点击页面上的转人工按钮。",
        }) {
            assertTrue(findClaim(ok) == null, "误伤了正当建议：" + ok + "（命中 " + findClaim(ok) + "）");
        }
    }

    /**
     * 服务端书写的、<b>会出现在买家眼前</b>的 AI 态文案。
     *
     * <p><b>{@code SYSTEM_PROMPT} 刻意不在表内</b>：它是要念给模型听的规则，里面<b>必须</b>
     * 出现「已为您转接真人」这类反例（否则模型没有禁止样本）。把它并进来扫，等于要求提示词
     * 不许写出要禁止的话 —— 断言会自我矛盾。提示词由下面 {@code systemPrompt_} 那条单独管。</p>
     */
    private static Map<String, String> aiStateTexts() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("AiChatService.EMPTY_REPLY_TEXT", AiChatService.EMPTY_REPLY_TEXT);
        texts.put("AiChatStreamService.RATE_LIMIT_MESSAGE", AiChatStreamService.RATE_LIMIT_MESSAGE);
        texts.put("AiChatStreamService.TIMEOUT_MESSAGE", AiChatStreamService.TIMEOUT_MESSAGE);
        texts.put("AiChatStreamService.TOO_MANY_MESSAGE", AiChatStreamService.TOO_MANY_MESSAGE);
        texts.put("AiChatStreamService.UPSTREAM_MESSAGE", AiChatStreamService.UPSTREAM_MESSAGE);
        texts.put("AiChatStreamService.GENERIC_TOOL_LABEL", AiChatStreamService.GENERIC_TOOL_LABEL);
        AiChatStreamService.TOOL_LABELS.forEach((k, v) -> texts.put("TOOL_LABELS." + k, v));
        return texts;
    }

    /** H11 主体：服务端书写的 AI 态文案逐条过扫描。 */
    @Test
    @DisplayName("全部服务端 AI 态文案均不含谎报字样")
    void allServerAuthoredAiStateTexts_areHonest() {
        Map<String, String> texts = aiStateTexts();
        texts.forEach((name, text) -> {
            String hit = findClaim(text);
            assertTrue(hit == null, name + " 含谎报字样「" + hit + "」：" + text);
        });

        // 防"枚举漏项"：新增 AI 态文案却忘了加进来，本测试会静默失效。
        // 钉住条目数下限（5 条服务端文案 + GENERIC_TOOL_LABEL + 2 条 TOOL_LABELS），改动时强制回来确认。
        assertTrue(texts.size() >= 8, "AI 态文案枚举变少了，检查是否漏项：" + texts.keySet());
    }

    /**
     * H11 的机制面：{@code SYSTEM_PROMPT} 必须真的禁止模型宣告转接。
     *
     * <p>光有静态扫描不够 —— 买家说「我要找客服」（不含锚点词 人工/转人工/真人/…，
     * 不会走 C4 分流）时，回答完全由模型生成，静态文案一条都扫不到。约束模型的唯一手段是提示词。</p>
     */
    @Test
    @DisplayName("SYSTEM_PROMPT 含有『不得宣告转接』的硬条款")
    void systemPrompt_forbidsClaimingTransfer() {
        String prompt = AiChatService.SYSTEM_PROMPT;
        assertTrue(prompt.contains("没有转接人工客服的能力"),
                "提示词缺少『你没有转接能力』的前置声明");
        assertTrue(prompt.contains("不由你宣告"),
                "提示词缺少『是否转人工由系统状态决定』的归属声明");
        assertTrue(findClaim(prompt) != null,
                "提示词里连一个反例都没有 —— 模型没有可模仿的禁止样本");
    }

    /**
     * 范围边界：人工通道的文案<b>刻意不在</b> AI 态枚举里。
     *
     * <p>让"不扫"成为<b>写明的决定</b>而非漏掉：这些串在真的建了 pending_human 行之后才下发
     * （见 {@code ChatHumanRouterImpl} 的分支），说的是真事。哪天有人图省事把
     * {@code AiSupportSessionServiceImpl} 的 TIP_* 并进 {@link #aiStateTexts()}，这条会先红。</p>
     */
    @Test
    @DisplayName("人工通道文案不属 H11 范围（诚实文案，不该被扫）")
    void humanChannelTexts_areOutOfScope() {
        Map<String, String> aiTexts = aiStateTexts();
        for (String tip : new String[]{
                AiSupportSessionServiceImpl.TIP_PENDING,
                AiSupportSessionServiceImpl.TIP_OFFLINE,
                AiSupportSessionServiceImpl.TIP_EXISTING_MESSAGE_LEFT,
                AiSupportSessionServiceImpl.TIP_EXISTING_HUMAN_ACTIVE,
        }) {
            assertTrue(!aiTexts.containsValue(tip),
                    "人工通道文案被并进了 AI 态扫描表：" + tip);
        }
        // TIP_PENDING 说的是真事（真建了 pending_human 行才发），所以它本就不该被词表拦下；
        // 这里显式确认词表没有宽到会误伤它。
        assertTrue(findClaim(AiSupportSessionServiceImpl.TIP_PENDING) == null,
                "词表宽到会误伤人工通道的诚实提示，需收窄："
                        + AiSupportSessionServiceImpl.TIP_PENDING);
    }
}
