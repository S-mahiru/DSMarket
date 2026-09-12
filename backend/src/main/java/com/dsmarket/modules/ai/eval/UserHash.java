package com.dsmarket.modules.ai.eval;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * userId 假名化（REQ-20260908-C5 §2：「用户标识 <b>哈希</b>（不用明文 userId；标注侧本地再解）」）。
 *
 * <p><b>为什么必须带盐</b>：{@code userId} 是 BIGINT 自增主键，裸 {@code SHA-256("5")} 的整个
 * 取值空间可以被毫秒级枚举反查 —— 对自增 ID 做无盐哈希，<b>等于没哈希</b>，只是把明文换成了
 * 另一种写法。加盐 HMAC 后，只拿到日志（拿不到盐）的人无法枚举；而标注侧持有盐，可对已知
 * userId 重算建表完成 §2 说的"本地再解"。</p>
 *
 * <p><b>诚实边界（防论文被拆穿）</b>：这是<b>假名化</b>（pseudonymization），<b>不是匿名化</b>。
 * 盐就在 application.yml / 环境变量里，能读到配置的人就能反查。§7 已把"敏感字段本地环境存储"
 * 列为前提（A11 合规），本条与之同口径。用带盐 HMAC 而不是随机 UUID 映射，是为了让哈希
 * <b>跨批次稳定</b> —— 否则 P2-H3 沉淀曲线（批次 vs 命中率）没法按用户 join。</p>
 *
 * <p><b>换盐即断链</b>：改 {@code ai.eval.hash-salt} 会让全部历史哈希对不上新日志。
 * 论文数据要能 join，定了就别改。</p>
 */
public final class UserHash {

    /** 取 HMAC 前 8 字节（16 个十六进制字符）：碰撞概率对本地量级足够，且比全量 64 字符好读 */
    private static final int HEX_CHARS = 16;

    private UserHash() {
    }

    /**
     * @param userId 明文用户 id（只在本方法内使用，不落任何日志）
     * @param salt   配置里的盐
     * @return 16 位十六进制小写哈希；算法不可用时返回固定串（见下）
     */
    public static String of(long userId, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HEX_CHARS);
            for (int i = 0; i < HEX_CHARS / 2; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // HmacSHA256 是 JDK 必备算法，正常装配走不到这里。真走到了也绝不能把主流程带下去
            // （C5 §1"写日志失败仅记日志"）。返回固定串而非明文 userId：宁可数据不可用，
            // 也不让明文标识从这条兜底路径漏出去。
            return "hash-unavailable";
        }
    }
}
