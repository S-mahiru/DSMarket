package com.dsmarket.modules.ai.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.ai.dto.AiIssueVO;
import com.dsmarket.modules.ai.dto.IssueAdoptRequest;

import java.util.Map;

/**
 * AI 客服问题池服务（C3 数据飞轮）。
 *
 * <p>切片 1：采集两路（自动低置信 + R2 点踩）——采集一律<b>静默失败</b>：写库异常仅记日志，
 * 不影响对话主流程。切片 2：R3 后台复核（列表/采纳/忽略）与 F6 定时向量化兜底。</p>
 */
public interface AiIssueService {

    /**
     * C3-F1：低置信自动采集。由 C1 SSE 低置信短路（search_knowledge NOT_COVERED →
     * fallback(FAQ)→suggest(LOW_CONF)）触发，写入一条 {@code source=LOW_CONF, status=pending}。
     *
     * <p>幂等/停采（主 §4.4 + C3-F1）：问句 blank → skip；同 user+question 已有记录（任意状态）→ skip；
     * 问句全局已被处置（adopted/ignored）→ skip。异常只记日志。</p>
     *
     * @param userId   提问人（登录态必有）
     * @param question 被采集问句：优先模型抽取的干净问句（无"查知识库"类指令噪音），无则整句原问句
     */
    void collectLowConfidence(Long userId, String question);

    /**
     * C3-F2：点踩采集。由 {@code POST /api/v1/ai/feedback {satisfied:false}} 触发：凭 replyId+userId
     * 在 Redis 会话轮条目回查该轮原问句 userContent → 写入 {@code source=DISLIKE, reply_id}。
     *
     * <p>回查不到/超窗/原问句空 → 200 静默忽略（防遍历探测 replyId）；同 user+reply 重复点踩 → skip；
     * 问句全局已被处置 → 停采。异常只记日志。</p>
     *
     * <p><b>返回值给谁用</b>（2026-09-10，C4 §4.6 F6 触发③）：{@code true} = <b>识别到一次新鲜点踩</b>，
     * 调用方据此累加"未解决信号"计数（达阈值 → {@code suggest("UNRESOLVED")}）。原为 {@code void}，
     * 改 {@code boolean} 是为了<b>复用本方法已有的去重</b>——同 user+reply 幂等判定 + 唯一索引
     * 天然挡住连点虚增，另写一套去重是重复劳动。</p>
     *
     * <p><b>口径提醒</b>：这里的 {@code true} 与"问题池有没有真的落一行"<b>不是一回事</b>——
     * 问句已被处置（{@code adopted}/{@code ignored}）时问题池停采，但买家<b>此刻仍然没被解决</b>，
     * 故该分支照样返回 {@code true}。问题池的"停采"是<b>采集策略</b>，未解决计数问的是<b>买家状态</b>。</p>
     *
     * @param userId  点踩人（R2 本人，JWT）
     * @param replyId 当轮 AI 回答 done 事件下发的 replyId
     * @return true = 一次新鲜的（可归因、非重复点击的）点踩；false = 未识别到信号
     */
    boolean collectDislike(Long userId, String replyId);

    // ------------------------------------------------------------ 切片 2：R3 后台复核

    /**
     * C3-F3：后台分页列表（仅 ADMIN）。可按 status / source 过滤，新→旧排序。
     *
     * @param status 状态过滤（pending/adopted/ignored），可空
     * @param source 来源过滤（LOW_CONF/DISLIKE），可空
     */
    PageResult<AiIssueVO> adminPage(long page, long size, String status, String source);

    /**
     * C3-F4：采纳 pending 问题 → 写知识（draft）+ 同步向量化 → 发布并迁移 adopted。
     *
     * <p>行锁 {@code FOR UPDATE} 串行化并发双采纳；采纳幂等（再点返回现有）；已忽略 → 409。
     * 向量化失败<b>不回滚</b>：draft 知识 + related 回填保留，issue 仍 pending，由 F6 定时兜底
     * 重试发布（对应 I6"已受理待向量化自动发布"）。</p>
     *
     * @return {@code {ok, knowledgeId, published}}：published=false 表示已受理、待 F6 自动发布
     */
    Map<String, Object> adopt(Long id, IssueAdoptRequest request);

    /**
     * C3-F5：忽略 pending 问题。pending → ignored；已处置（adopted/ignored）→ 幂等 200；不存在 → 404。
     */
    void ignore(Long id);

    /**
     * C3-F6：定时向量化兜底（由 {@code task/AiIssueRetryTask} 每分钟驱动，可单测方法）。
     * 扫描 pending + 已关联 draft 且未向量化的知识行 → 逐条向量化：成功 → 发布 + casAdopt；
     * 失败 → incrementRetry，达 {@code ai.issue.retry-max} 上限 log.warn 告警（仍每分钟重试）。
     *
     * <p>非事务：逐行自动提交，单行失败不影响其它行。</p>
     *
     * @return 本次自动发布条数
     */
    int retryVectorization();
}
