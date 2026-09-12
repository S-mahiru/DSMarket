package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 转人工锚点词命中判定（C4 切片 3，主 REQ §5.1 HUMAN_REQUEST + C4 §4.6 F6 触发②）。
 *
 * <p>词集与误伤剔除表<b>都在配置里</b>（{@code ai.support.human-request-anchors} /
 * {@code human-request-emotion-anchors} / {@code -exclusions}）：§10 A10 把"锚点词集"明确列为
 * <b>误伤率</b>可调项，所以调整口径只改 yml，不碰代码。</p>
 *
 * <p><b>两个词集都判为"自动转人工"</b>（2026-09-10 裁决）：§5.1 的 HUMAN_REQUEST 词集本就是
 * 转人工锚点；§4.6 的情绪/投诉词集原设计只发 suggest 气泡，但它与 §5.1 重叠（"投诉"两边都在，
 * 行为相反），裁决为<b>一律直接转人工</b>，不再走气泡。两类在配置里分开维护只为留痕可追溯到
 * 各自的需求条款，判定本身不做区分。</p>
 *
 * <p><b>先剔误伤再匹配</b>：{@code 人工} 是子串，直接匹配会把"人工呼吸/人工智能/人工湖"
 * 也判成转人工（主 REQ §5.1 原文点名"人工呼吸"）。做法是把误伤短语先替换成空格再匹配，
 * 而不是用正则词边界——中文没有词边界，{@code 我要人工客服} 这种紧贴的写法必须仍能命中。</p>
 *
 * <p>替换成空格而非删除，是为了不制造新的子串邻接：若直接删掉，{@code 人工} + {@code 呼吸}
 * 拼接后可能意外凑出别的词。空格是安全的中性填充。</p>
 */
@Component
@RequiredArgsConstructor
public class AnchorWordMatcher {

    private final AiProperties properties;

    /**
     * @param content 买家原文（允许 null/空）
     * @return 命中的锚点词（供日志与留痕定位是哪条口径触发）；未命中 → null
     */
    public String match(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        AiProperties.Support cfg = properties.getSupport();
        String sanitized = content;
        for (String exclusion : cfg.getHumanRequestExclusions()) {
            if (exclusion != null && !exclusion.isBlank()) {
                sanitized = sanitized.replace(exclusion, " ");
            }
        }
        // 按长度降序匹配：让"投诉电话"赢过"投诉"、"人工客服"赢过"人工"，返回的词才定位得到具体口径。
        // 排序的是副本，不改配置里的原列表（那是 yml 的声明顺序，不该被这里改写）。
        // 两个词集合并去重（"投诉"两边都有）：判定不做区分，分开维护只为留痕可追溯。
        // 用 LinkedHashSet 保证同长度词的返回结果按声明顺序稳定，不随哈希漂移。
        Set<String> merged = new LinkedHashSet<>(cfg.getHumanRequestAnchors());
        merged.addAll(cfg.getHumanRequestEmotionAnchors());
        List<String> anchors = new ArrayList<>(merged);
        anchors.sort(Comparator.comparingInt(String::length).reversed());
        for (String anchor : anchors) {
            if (anchor != null && !anchor.isBlank() && sanitized.contains(anchor)) {
                return anchor;
            }
        }
        return null;
    }
}
