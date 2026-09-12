package com.dsmarket.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.common.util.VectorText;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AiIssueVO;
import com.dsmarket.modules.ai.dto.IssueAdoptRequest;
import com.dsmarket.modules.ai.entity.AiIssuePool;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.enums.AiIssueSource;
import com.dsmarket.modules.ai.enums.AiIssueStatus;
import com.dsmarket.modules.ai.enums.AiKnowledgeStatus;
import com.dsmarket.modules.ai.mapper.AiIssuePoolMapper;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import com.dsmarket.modules.ai.service.AiIssueService;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.RoundWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问题池服务实现（C3 数据飞轮）。
 *
 * <p>切片 1 采集一律静默（幂等判定命中或 DB 异常都不抛给主流程）。切片 2 R3 复核：
 * 采纳 = 行锁 + 写知识 draft + 同步向量化即发布（失败不回滚，F6 兜底）；忽略 = CAS；F6 = 每分钟扫描兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIssueServiceImpl implements AiIssueService {

    /** F6 单轮扫描上限（条），避免兜底任务单轮刷太多把上游压垮 */
    private static final int F6_SCAN_LIMIT = 50;

    private static final String NOT_FOUND_MSG = "问题条目不存在";

    private final AiIssuePoolMapper issueMapper;
    private final AiSessionStore sessionStore;
    private final AiKnowledgeMapper knowledgeMapper;
    private final EmbeddingClient embeddingClient;
    private final AiProperties properties;

    // ------------------------------------------------------------ 切片 1：采集（静默）

    @Override
    public void collectLowConfidence(Long userId, String question) {
        String q = trimToNull(question);
        if (q == null) {
            return;
        }
        try {
            // 幂等/停采：同 user+question 已有记录，或问句全局已处置 → 不重复写
            if (issueMapper.countByUserQuestion(userId, q) > 0) {
                log.info("[ai][c3] 低置信采集跳过：同用户同问句已存在. userId={}", userId);
                return;
            }
            if (issueMapper.countAdoptedOrIgnoredByQuestion(q) > 0) {
                log.info("[ai][c3] 低置信采集跳过：问句已处置(adopted/ignored 停采). question={}", q);
                return;
            }
            AiIssuePool issue = new AiIssuePool();
            issue.setUserId(userId);
            issue.setQuestion(q);
            issue.setSource(AiIssueSource.LOW_CONF_VALUE);
            issue.setStatus(AiIssueStatus.PENDING_VALUE);
            issueMapper.insert(issue);
            log.info("[ai][c3] 低置信采集入库. userId={} question={}", userId, q);
        } catch (Exception e) {
            // C3 §5：采集失败仅记日志，不影响对话
            log.warn("[ai][c3] 低置信采集失败（不影响对话）. userId={} question={}", userId, q, e);
        }
    }

    @Override
    public boolean collectDislike(Long userId, String replyId) {
        if (userId == null || trimToNull(replyId) == null) {
            return false;
        }
        try {
            // 回查（主 §2.4）：Redis 会话轮条目找 replyId → 原问句 userContent。
            // 【顺序】必须先解出问句，再判幂等 —— 幂等键含问句维度（#127）；
            // 旧版把幂等闸放在这里之前，导致闸根本不知道这一轮问的是什么，只能按 replyId 一刀切。
            ChatRound round = RoundWindow.findRound(sessionStore.loadRounds(userId), replyId);
            if (round == null || trimToNull(round.getUserContent()) == null) {
                // 回查失败/超窗 → 200 静默忽略（防遍历探测 replyId）。
                // 这里也返回 false：归因不到问句，无法确认这是一次"本轮未解决"，宁可少数。
                // 也正因为归因不到问句，就没有东西可入池 —— 这不是漏采，是"无数据可采"。
                log.info("[ai][c3] 点踩回查未命中/已超窗，静默忽略. userId={} replyId={}", userId, replyId);
                return false;
            }
            String q = round.getUserContent().trim();
            // 幂等：同 user + 同 replyId + **同问句** 已点踩 → 不重复（含已 adopted/ignored；并发由唯一索引兜底）。
            // 问句维度是 #127 的修复点：replyId 是会话内轮次号、跨会话会回收，
            // 只按 (user, replyId) 判重会把"新会话第 N 轮的新问题"误判成"旧会话第 N 轮的重复点踩"而永久丢弃。
            // 返回 false 同时挡住 F6 触发③ 的"连点虚增"——这里已经查过了，不必另写一套去重。
            if (issueMapper.countByUserReplyAndQuestion(userId, replyId, q) > 0) {
                log.info("[ai][c3] 点踩采集跳过：同回复同问句已点踩. userId={} replyId={}", userId, replyId);
                return false;
            }
            if (issueMapper.countAdoptedOrIgnoredByQuestion(q) > 0) {
                // 问题池停采（已处置），但买家**此刻仍然没被解决** → 未解决计数照样 +1，故 return true。
                // 两者口径不同：问题池管"还要不要收进池子"，未解决计数管"买家还卡着没卡着"。
                log.info("[ai][c3] 点踩采集跳过：问句已处置(adopted/ignored 停采). question={}", q);
                return true;
            }
            AiIssuePool issue = new AiIssuePool();
            issue.setUserId(userId);
            issue.setQuestion(q);
            issue.setSource(AiIssueSource.DISLIKE_VALUE);
            issue.setReplyId(replyId);
            issue.setStatus(AiIssueStatus.PENDING_VALUE);
            issueMapper.insert(issue);
            log.info("[ai][c3] 点踩采集入库. userId={} replyId={} question={}", userId, replyId, q);
            return true;
        } catch (Exception e) {
            // C3 §5：采集失败仅记日志，不影响主流程
            log.warn("[ai][c3] 点踩采集失败（不影响主流程）. userId={} replyId={}", userId, replyId, e);
            return false;
        }
    }

    // ------------------------------------------------------------ 切片 2：R3 后台复核

    @Override
    public PageResult<AiIssueVO> adminPage(long page, long size, String status, String source) {
        LambdaQueryWrapper<AiIssuePool> wrapper = new LambdaQueryWrapper<AiIssuePool>()
                .orderByDesc(AiIssuePool::getId);
        if (isNotBlank(status)) {
            wrapper.eq(AiIssuePool::getStatus, status);
        }
        if (isNotBlank(source)) {
            wrapper.eq(AiIssuePool::getSource, source);
        }
        Page<AiIssuePool> result = issueMapper.selectPage(new Page<>(page, size), wrapper);
        List<AiIssueVO> vos = result.getRecords() == null ? List.of()
                : result.getRecords().stream().map(this::toVo).toList();
        return PageResult.of(vos, result.getTotal(), page, size);
    }

    @Override
    @Transactional
    public Map<String, Object> adopt(Long id, IssueAdoptRequest request) {
        AiIssuePool issue = lockOrNull(id);
        if (issue == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), NOT_FOUND_MSG);
        }
        if (AiIssueStatus.ADOPTED_VALUE.equals(issue.getStatus())) {
            // 幂等：已采纳 → 返回现有（published 反映关联知识当前状态）
            log.info("[ai][c3] 采纳幂等返回：issue 已采纳. issueId={} kid={}", id, issue.getRelatedKnowledgeId());
            return adoptResult(issue.getRelatedKnowledgeId(), isKnowledgePublished(issue.getRelatedKnowledgeId()));
        }
        if (!AiIssueStatus.PENDING_VALUE.equals(issue.getStatus())) {
            // 已忽略：终态不可再采纳
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), "该问题已忽略，不可采纳");
        }

        // 采纳写知识（draft）+ 先建即回填 related_knowledge_id（实施放宽：供 F6 兜底定位，见 DECISION）
        AiKnowledge knowledge = new AiKnowledge();
        knowledge.setCategory(request.getCategory());
        knowledge.setQuestion(issue.getQuestion().trim());
        knowledge.setAnswer(request.getAnswer().trim());
        knowledge.setFaq(Boolean.FALSE);
        knowledge.setStatus(AiKnowledgeStatus.DRAFT_VALUE);
        knowledgeMapper.insert(knowledge);
        Long kid = knowledge.getId();
        issueMapper.linkKnowledge(id, kid);
        log.info("[ai][c3] 采纳受理：已建知识 draft 并回填. issueId={} kid={}", id, kid);

        // 同步向量化：成功 → 发布 + adopted；失败/空 → 不回滚（draft+pending 留存，F6 每分钟兜底）
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(knowledge.getQuestion()));
            if (vectors == null || vectors.isEmpty()) {
                log.warn("[ai][c3] 采纳受理：向量化返回空，保留草稿待 F6. issueId={} kid={}", id, kid);
                return adoptResult(kid, false);
            }
            int updated = knowledgeMapper.publishWithEmbedding(kid, VectorText.of(vectors.get(0)));
            if (updated == 0) {
                log.warn("[ai][c3] 采纳受理：知识行不存在或已软删，向量化写入 0 行. issueId={} kid={}", id, kid);
                return adoptResult(kid, false);
            }
            issueMapper.casAdopt(id);
            log.info("[ai][c3] 采纳成功并同步发布. issueId={} kid={}", id, kid);
            return adoptResult(kid, true);
        } catch (Exception e) {
            // 向量化失败不回滚：draft 知识 + related 回填保留（tx 提交），F6 兜底（I6 已受理待自动发布）
            log.warn("[ai][c3] 采纳受理但向量化失败，保留草稿待 F6 兜底. issueId={} kid={}", id, kid, e);
            return adoptResult(kid, false);
        }
    }

    @Override
    public void ignore(Long id) {
        AiIssuePool issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), NOT_FOUND_MSG);
        }
        if (AiIssueStatus.PENDING_VALUE.equals(issue.getStatus())) {
            issueMapper.casIgnore(id); // pending→ignored；并发已被处置（adopted）→ 0 行，等同幂等
        }
        // adopted/ignored → 幂等 200，不动作
    }

    @Override
    public int retryVectorization() {
        List<AiIssuePool> issues = issueMapper.selectRetryableIssues(F6_SCAN_LIMIT);
        if (issues.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (AiIssuePool issue : issues) {
            try {
                List<float[]> vectors = embeddingClient.embed(List.of(issue.getQuestion().trim()));
                if (vectors == null || vectors.isEmpty()) {
                    bumpRetry(issue);
                    continue;
                }
                int updated = knowledgeMapper.publishWithEmbedding(
                        issue.getRelatedKnowledgeId(), VectorText.of(vectors.get(0)));
                if (updated == 0) {
                    // 关联 draft 行没了（已删/并发处置）→ 计数后下轮不再命中（JOIN deleted=0 会排除）
                    bumpRetry(issue);
                    continue;
                }
                issueMapper.casAdopt(issue.getId());
                published++;
                log.info("[ai][c3] F6 兜底发布成功. issueId={} kid={}", issue.getId(), issue.getRelatedKnowledgeId());
            } catch (Exception e) {
                // 单行失败不影响其它行（本方法非事务，逐行自动提交）
                bumpRetry(issue);
            }
        }
        return published;
    }

    // ------------------------------------------------------------ 私有

    private AiIssuePool lockOrNull(Long id) {
        AiIssuePool row = issueMapper.lockById(id);
        if (row == null) {
            log.info("[ai][c3] 采纳目标不存在/已软删. issueId={}", id);
        }
        return row;
    }

    private boolean isKnowledgePublished(Long kid) {
        if (kid == null) {
            return false;
        }
        AiKnowledge k = knowledgeMapper.selectById(kid);
        return k != null && AiKnowledgeStatus.PUBLISHED_VALUE.equals(k.getStatus());
    }

    private Map<String, Object> adoptResult(Long knowledgeId, boolean published) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("knowledgeId", knowledgeId);
        m.put("published", published);
        return m;
    }

    /** 兜底重试计数 +1；首次越过 {@code ai.issue.retry-max} 上限时 log.warn 告警（仍每分钟重试）。 */
    private void bumpRetry(AiIssuePool issue) {
        int prev = issue.getRetryCount() == null ? 0 : issue.getRetryCount();
        int retryMax = properties.getIssue().getRetryMax();
        issueMapper.incrementRetry(issue.getId());
        int next = prev + 1;
        if (prev < retryMax && next >= retryMax) {
            log.warn("[ai][c3] F6 向量化重试已达上限 {} 次，仍保留每分钟重试（修复后自动发布）. " +
                    "issueId={} kid={}", retryMax, issue.getId(), issue.getRelatedKnowledgeId());
        }
    }

    private AiIssueVO toVo(AiIssuePool row) {
        AiIssueVO vo = new AiIssueVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setQuestion(row.getQuestion());
        vo.setSource(row.getSource());
        AiIssueSource src = AiIssueSource.fromValueOrNull(row.getSource());
        vo.setSourceName(src == null ? row.getSource() : src.getDisplayName());
        vo.setStatus(row.getStatus());
        AiIssueStatus st = AiIssueStatus.fromValueOrNull(row.getStatus());
        vo.setStatusName(st == null ? row.getStatus() : st.getDisplayName());
        vo.setReplyId(row.getReplyId());
        vo.setRelatedKnowledgeId(row.getRelatedKnowledgeId());
        vo.setRetryCount(row.getRetryCount());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
