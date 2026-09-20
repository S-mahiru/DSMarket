-- ============================================
-- V9：修正 DISLIKE 幂等键——补上"问句"维度（#127 跨会话 replyId 回收致点踩被静默丢弃）
-- 前置：V7（ds_ai_issue_pool）
-- 缺陷：#127。`RoundWindow.nextReplyId` 是**会话内**从 "1" 递增的轮次号（Redis，TTL 30min），
--      而 V7 的 `uk_ai_issue_pool_dislike_reply` 把 `(user_id, reply_id)` 当**永久唯一**用，
--      且 `countByUserReply` 的 WHERE **不含 status**（注释明写"含已 adopted/ignored"）。
--      后果：买家只要在任意会话第 N 轮点过踩，此后**所有**会话第 N 轮的点踩都被永久静默丢弃
--      （`AiIssueServiceImpl.collectDislike` 第一道闸 return false）→ 漏采（非脏数据），
--      并连带 `AiController.feedback` 不累加未解决计数 → C4 §4.6 触发③ 迟一拍。
--      真机 A-B 复现见 docs/ai-experiments/127-replyid-recycle/（21 项断言）。
-- 修法（#127 裁决"乙"）：幂等键补上问句维度 —— 把"跨会话撞号"与"会话内连点"分开。
--      · 同会话同轮连点（同问句）→ 仍去重（连点虚增防护不变）
--      · 跨会话不同问句撞同一轮次号 → **不再误挡**（这正是缺陷）
--      · 跨会话同问句撞号 → 仍去重（同一问题一条池记录，与 LOW_CONF 的 countByUserQuestion 口径一致）
--      REQ §4.2 的"处理"行本就把回查限定在 `ai:session:{userId}` 内（replyId 是**会话内**标识），
--      本迁移是让持久层回到 REQ 的心智模型，**不是**改需求。
-- 为何用 md5(question) 而非 question 原文：B-tree 索引条目上限约 2704 字节，
--      question 是 TEXT，AI 态 content 上限 500 字（全 4 字节字符 = 2000 字节），余量不足；
--      且问句是自由文本，原文进索引还会膨胀。md5 定长 32 字节，碰撞概率可忽略。
-- 幂等：可重复执行（DROP INDEX IF EXISTS + CREATE）
-- 权威：DECISION-20260910-C5-留痕切片2设计 §6.4（选项乙）；实施记录见文档同节
-- ============================================

-- 旧索引名本身就写明了"只按 user+reply"—— 一并更名，避免后来人按名字理解成旧语义
DROP INDEX IF EXISTS uk_ai_issue_pool_dislike_reply;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_issue_pool_dislike_reply_question
    ON ds_ai_issue_pool (user_id, reply_id, md5(question))
    WHERE source = 'DISLIKE' AND deleted = 0;

COMMENT ON INDEX uk_ai_issue_pool_dislike_reply_question IS
    'DISLIKE 幂等：#127 修复后按 (user_id, reply_id, md5(question)) 去重——replyId 是会话内轮次号会跨会话回收，故必须带问句维度';
