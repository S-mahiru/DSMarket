package com.dsmarket.modules.ai.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6 片段组织与冲突消解（C2 §3.6）：锚定组=top1 类目、answer 去重（相同/包含）、≤3 截断、
 * 同主题异口径冲突→低置信空、跨类目并存不构成冲突、非锚定组不入段。输入须已按分降序。
 */
class KnowledgeOrganizerTest {

    /** 构造一个候选：需保证 organize 输入已按 fusedScore 降序（调用方依序给出）。 */
    private FusionHit hit(long id, String category, String question, String answer) {
        KnowledgeSegment seg = new KnowledgeSegment();
        seg.setId(id);
        seg.setCategory(category);
        seg.setCategoryName(category);
        seg.setQuestion(question);
        seg.setAnswer(answer);
        FusionHit h = new FusionHit();
        h.setSegment(seg);
        return h;
    }

    @Test
    void empty_isNotCovered() {
        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(List.of());
        assertFalse(o.isCovered());
        assertTrue(o.getSegments().isEmpty());
    }

    @Test
    void anchorGroupIsTop1Category_othersExcluded() {
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "怎么退款", "A1"),
                hit(2, "after_sale", "退款到账时间", "A2"),
                hit(3, "shipping", "怎么退款", "B")); // 跨类目同问，非锚定组 → 不进段

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertTrue(o.isCovered());
        assertEquals(List.of(1L, 2L), o.getSegments().stream().map(KnowledgeSegment::getId).toList(),
                "只出锚定组（after_sale），shipping 候选被排除");
    }

    @Test
    void duplicateAnswer_identicalOrContained_keepsHighest() {
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "退货怎么退", "支持7天无理由退货退款"),
                hit(2, "after_sale", "能退吗", "支持7天无理由退货退款"),  // 与 id1 answer 相同
                hit(3, "after_sale", "期限多久", "7天无理由退货退款"));  // 被 id1 answer 包含

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertTrue(o.isCovered());
        assertEquals(List.of(1L), o.getSegments().stream().map(KnowledgeSegment::getId).toList(),
                "answer 相同/互为包含只留最高分一条");
    }

    @Test
    void capsAnchorGroupAtThree() {
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "Q1", "A1"),
                hit(2, "after_sale", "Q2", "A2"),
                hit(3, "after_sale", "Q3", "A3"),
                hit(4, "after_sale", "Q4", "A4"));

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertTrue(o.isCovered());
        assertEquals(List.of(1L, 2L, 3L), o.getSegments().stream().map(KnowledgeSegment::getId).toList(),
                "输出 ≤3 条，其余不进 LLM 上下文");
    }

    @Test
    void conflictingAnswers_sameThemeInAnchor_isLowConfidenceEmpty() {
        // R9：同 question 两条互斥标准答案（同锚定组、均高分）→ 不并喂，整轮低置信
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "退货可以吗", "可以，7天内无理由"),
                hit(2, "after_sale", "退货可以吗", "不可以，定制商品除外"));

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertFalse(o.isCovered());
        assertTrue(o.getSegments().isEmpty());
    }

    @Test
    void crossCategory_sameQuestion_doesNotConflict() {
        // R10：同问涉"退款+运费"分落售后/物流两目 → 各取锚定一条，不构成矛盾
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "退款还要退运费吗", "支持整单退款，含已付运费"),
                hit(3, "shipping", "退款还要退运费吗", "运费险可赔付退货运费"));

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertTrue(o.isCovered());
        assertEquals(List.of(1L), o.getSegments().stream().map(KnowledgeSegment::getId).toList(),
                "冲突判定限定锚定组内；shipping 条目不入本段（单锚定纪律）");
    }

    @Test
    void conflictOnSecondaryTheme_doesNotSinkDistinctTopTheme() {
        // 真机发现的场景（Q1）：被问主题(A)是单口径，但同锚定组混入另一主题(预售)的互斥对，
        // 不应拖垮整轮；按单口径纪律只取该主题最高分一条，跳过其低分冲突成员。
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", "黑海商城支持七天无理由退货吗", "支持，7天内无理由"),
                hit(2, "after_sale", "预售商品能否退款", "支持随时退款"),
                hit(3, "after_sale", "预售商品能否退款", "发货后不支持退款")); // 与 id2 同主题异口径

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertTrue(o.isCovered(), "top1 主题单口径 → 不应因旁路冲突对误杀");
        assertEquals(List.of(1L, 2L), o.getSegments().stream().map(KnowledgeSegment::getId).toList(),
                "取 top 主题 + 预售主题最高分一条；同主题低分冲突成员被跳过，≤3 且单口径");
    }

    @Test
    void themeKey_ignoresSurroundingWhitespace() {
        // answer 须为整句（避免"互为包含"去重误伤单字口径），question 侧空白差异才是被测对象
        List<FusionHit> fused = List.of(
                hit(1, "after_sale", " 退款能到账吗 ", "标准商品签收后7天到账"),
                hit(2, "after_sale", "退款能到账吗", "定制商品按确认函约定到账"));

        KnowledgeOrganizer.Outcome o = KnowledgeOrganizer.organize(fused);

        assertFalse(o.isCovered(), "trim 后同 theme、异口径 → 冲突 → 低置信空");
    }
}
