package com.dsmarket.modules.ai.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F6 片段组织与冲突消解 —— 纯静态、确定性（C2 §3.6）。
 *
 * <p>服务端权威规则，模型只见已消解的单一口径片段：
 * <ol>
 *   <li><b>锚定组</b> = top1 融合候选所在 category 的全部候选（组内保持融合分降序）；</li>
 *   <li><b>组内去重</b>：answer 相同/互为包含的重复条目只保留最高分一条；</li>
 *   <li><b>冲突消解（R9）</b>：<b>top1 主题</b>（被问主题，question 归一后相等）在去重后的锚定组内
 *       存在多条互斥口径 → 不并喂、整轮判低置信（返回空）；非 top 主题的冲突对不拖垮整轮，
 *       只按单口径纪律跳过其低分成员；</li>
 *   <li>输出 = 锚定组 <b>≤3</b> 条、同主题至多一条口径，其余候选不进 LLM 上下文。</li>
 * </ol>
 * 跨类目（R10：同 theme 条目散在不同 category）不在同一锚定组内 → 不构成冲突。
 * 文档化假设：REQ 未定义"同主题"，取 question 文本归一（trim + 去内部空白）相等为确定可测键。</p>
 */
public final class KnowledgeOrganizer {

    private KnowledgeOrganizer() {
    }

    /**
     * @param fused 融合后已按分降序的候选（RrfFuser 输出，FINAL_TOP 截断后）
     * @return 组织结果：covered=false 表示空输入或 <b>top1 主题</b>冲突（被问主题库内口径互斥）；
     *         segments ≤3、同主题至多一条口径（covered=true 时）
     */
    public static Outcome organize(List<FusionHit> fused) {
        if (fused == null || fused.isEmpty()) {
            return Outcome.notCovered();
        }
        FusionHit top = fused.get(0);
        String anchorCategory = top.getSegment().getCategory();

        List<FusionHit> anchor = new ArrayList<>();
        for (FusionHit hit : fused) {
            if (anchorCategory.equals(hit.getSegment().getCategory())) {
                anchor.add(hit);
            }
        }

        // 组内去重：answer 相同/互为包含 → 只保留更高分（anchor 已按分降序，先到即更高分）
        List<FusionHit> kept = new ArrayList<>();
        List<String> keptAnswers = new ArrayList<>();
        for (FusionHit hit : anchor) {
            String answer = hit.getSegment().getAnswer();
            if (answer != null && !answer.isBlank() && containsAny(keptAnswers, answer)) {
                continue;
            }
            kept.add(hit);
            if (answer != null) {
                keptAnswers.add(answer);
            }
        }

        // R9 冲突消解：只对被问主题（top1 主题）生效 —— 库内该主题口径互斥才整轮低置信。
        // 非 top 主题的冲突对不拖垮整轮（避免小语料里同 category 语义近的干扰对造成误杀），
        // 由下方单口径纪律把同主题多余成员挤出喂给集。真机冒烟验证见 DECISION-20260908-C2-检索引擎。
        String topTheme = themeKey(kept.get(0).getSegment().getQuestion());
        Set<String> topThemeAnswers = new HashSet<>();
        for (FusionHit hit : kept) {
            if (topTheme.equals(themeKey(hit.getSegment().getQuestion()))) {
                topThemeAnswers.add(hit.getSegment().getAnswer());
            }
        }
        if (topThemeAnswers.size() >= 2) {
            return Outcome.notCovered();
        }

        // 单口径纪律：按分降序贪婪取 ≤3，同主题至多一条（取最高分那一条，冲突低分成员自然跳过）
        List<KnowledgeSegment> segments = new ArrayList<>();
        Set<String> emittedThemes = new HashSet<>();
        for (FusionHit hit : kept) {
            String theme = themeKey(hit.getSegment().getQuestion());
            if (emittedThemes.add(theme)) {
                segments.add(hit.getSegment());
                if (segments.size() == 3) {
                    break;
                }
            }
        }
        return Outcome.covered(segments);
    }

    /** answer 是否与任一已保留答案相同或互为包含。 */
    private static boolean containsAny(List<String> kept, String answer) {
        for (String k : kept) {
            if (k.equals(answer) || k.contains(answer) || answer.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 主题键：trim + 连续空白折一空格（中文查询无空白，主要防两侧/制表差异）。 */
    static String themeKey(String question) {
        if (question == null) {
            return "";
        }
        return question.trim().replaceAll("\\s+", " ");
    }

    /** F6 组织结果。 */
    public static final class Outcome {
        private final boolean covered;
        private final List<KnowledgeSegment> segments;

        private Outcome(boolean covered, List<KnowledgeSegment> segments) {
            this.covered = covered;
            this.segments = segments;
        }

        public static Outcome covered(List<KnowledgeSegment> segments) {
            return new Outcome(true, segments);
        }

        public static Outcome notCovered() {
            return new Outcome(false, List.of());
        }

        public boolean isCovered() {
            return covered;
        }

        public List<KnowledgeSegment> getSegments() {
            return segments;
        }
    }
}
