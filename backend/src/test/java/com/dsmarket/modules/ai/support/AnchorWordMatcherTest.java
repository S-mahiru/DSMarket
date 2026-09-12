package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转人工锚点词判定单测（C4 切片 3，主 REQ §5.1 HUMAN_REQUEST）。
 *
 * <p>重点不在"命中"，而在<b>不误命中</b>：裸词 {@code 人工} / {@code 真人} 是子串，误伤是
 * §10 A10 明确点名的风险项，测试把已知误伤口径钉住。</p>
 */
class AnchorWordMatcherTest {

    private final AiProperties properties = new AiProperties();
    private final AnchorWordMatcher matcher = new AnchorWordMatcher(properties);

    @Test
    void hitsEveryWordInAuthoritativeSet() {
        // 主 REQ §5.1 权威词集逐条压一遍；返回的具体词就是触发口径（供日志与留痕定位）
        record Case(String content, String expected) {
        }
        List<Case> cases = List.of(
                new Case("我要找人工客服", "人工客服"),
                new Case("麻烦转人工", "转人工"),
                new Case("来个真人跟我说话", "真人"),
                new Case("帮我找个人处理一下", "找个人"),
                new Case("帮我找你们领导", "找你们领导"),
                new Case("我要投诉", "投诉"),
                new Case("给我投诉电话", "投诉电话"),
                new Case("再不解决我就打12315", "12315"));
        for (Case c : cases) {
            assertEquals(c.expected(), matcher.match(c.content()), "应命中：" + c.content());
        }
    }

    @Test
    void hitsEveryWordInF6EmotionSet() {
        // C4 §4.6 F6 触发②的情绪/投诉词集（2026-09-10 裁决：并入自动转人工，不再只发 suggest 气泡）
        record Case(String content, String expected) {
        }
        List<Case> cases = List.of(
                new Case("你们这个服务气死我了", "气死"),
                new Case("你们骗人，说好三天到", "你们骗人"),
                new Case("这次体验太差了", "太差"),
                new Case("再不处理我就给差评", "差评"),
                new Case("找你们客服退款都不行吗", "退款都不行"));
        for (Case c : cases) {
            assertEquals(c.expected(), matcher.match(c.content()), "应命中：" + c.content());
        }
    }

    @Test
    void overlappingWordComplaint_hitsViaBothSetsWithoutDuplicating() {
        // "投诉" 同时在 §5.1 锚点集与 §4.6 情绪集里——这正是当初的重叠陷阱。
        // 合并去重后只返回一个词，且结果稳定（不随哈希漂移）。
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            seen.add(matcher.match("我要投诉"));
        }
        assertEquals(Set.of("投诉"), seen, "20 次调用结果唯一且一致");
    }

    @Test
    void emotionSetIsConfigurable_independentlyOfAnchorSet() {
        // §4.6 词集标"可调"，且与 §5.1 锚点集**分开维护**（各自可追溯到需求条款）
        assertEquals(6, properties.getSupport().getHumanRequestEmotionAnchors().size(), "初始 6 条");
        properties.getSupport().setHumanRequestEmotionAnchors(List.of("气得不行"));

        assertEquals("气得不行", matcher.match("气得不行了"));
        assertNull(matcher.match("这次体验太差了"), "情绪词被换掉后不再命中");
        // 反向确认两套是独立的：换掉情绪集不影响 §5.1 锚点集，"投诉"仍命中
        assertEquals("投诉", matcher.match("我要投诉"), "「投诉」同属 §5.1 锚点集，换掉情绪集也仍命中");
    }

    @Test
    void prefersLongestAnchor_soTraceNamesTheExactRule() {
        // "投诉电话" 比 "投诉" 具体：若按声明顺序匹配会返回"投诉"，日志里就看不出是电话口径
        assertEquals("投诉电话", matcher.match("给我个投诉电话"));
        assertEquals("人工客服", matcher.match("你们人工客服在吗"));
    }

    @Test
    void excludesKnownFalsePositivesOfBareAnchors() {
        // 裸词"人工"/"真人"的典型误伤。这些句子完全正常，绝不能转人工
        assertNull(matcher.match("这个包是人工呼吸用的吗"), "人工呼吸");
        assertNull(matcher.match("你们用的是人工智能吗"), "人工智能");
        assertNull(matcher.match("有真人CS的装备吗"), "真人CS");
        assertNull(matcher.match("真人秀同款周边有吗"), "真人秀");
    }

    @Test
    void exclusionDoesNotSwallowTheRealAnchor() {
        // 剔除是"只剔误伤短语"，不是"整句作废"：同一句里既有误伤词又有真锚点，仍须命中
        assertEquals("人工客服", matcher.match("我要人工客服，不是要人工智能"));
        assertEquals("人工", matcher.match("人工呼吸我不会，叫人工来"));
    }

    @Test
    void plainQuestionsDoNotHit() {
        assertNull(matcher.match("我的订单什么时候发货"));
        assertNull(matcher.match("七天无理由退货怎么算时间"));
        assertNull(matcher.match("这个手机有货吗"));
    }

    @Test
    void nullAndBlankReturnNull() {
        assertNull(matcher.match(null));
        assertNull(matcher.match(""));
        assertNull(matcher.match("   "));
    }

    @Test
    void wordSetIsConfigurable_notHardcoded() {
        // §10 A10：锚点词集列在配置里，收敛误伤率只改配置不碰代码
        properties.getSupport().setHumanRequestAnchors(List.of("叫店长"));
        properties.getSupport().setHumanRequestExclusions(List.of());
        assertEquals("叫店长", matcher.match("叫店长出来"));
        assertNull(matcher.match("我要找人工客服"), "换掉词集后旧词不再命中");
    }

    @Test
    void exclusionsDoNotMutateConfigList() {
        // 匹配内部会按长度排序，但排的是副本——yml 里的声明顺序不该被运行时改写
        List<String> declared = List.copyOf(properties.getSupport().getHumanRequestAnchors());
        matcher.match("给我投诉电话");
        assertEquals(declared, properties.getSupport().getHumanRequestAnchors(), "配置列表顺序不被改写");
        assertTrue(declared.size() >= 9, "§5.1 权威词集共 9 条");
    }
}
