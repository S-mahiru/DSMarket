package com.dsmarket.modules.ai.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * userId 假名化单测（REQ-20260908-C5 §2「用户标识哈希」）。
 *
 * <p>重点不在"哈希函数对不对"（HMAC-SHA256 是 JDK 的），而在三条<b>口径</b>：
 * 跨批次稳定（否则沉淀曲线没法 join）、不同用户不撞、<b>换盐就断链</b>（提醒后来者别顺手改盐）。</p>
 */
class UserHashTest {

    @Test
    void stable_acrossCalls() {
        // §4.2 要按用户跨批次 join，哈希必须稳定 —— 用随机 UUID 做映射就做不到这一点
        assertEquals(UserHash.of(5L, "salt"), UserHash.of(5L, "salt"));
    }

    @Test
    void differentUsers_differentHashes() {
        assertNotEquals(UserHash.of(5L, "salt"), UserHash.of(6L, "salt"));
        // 相邻 id 也算得远（HMAC 的雪崩效应），不会出现"看着像"的相似串
        assertNotEquals(UserHash.of(100L, "salt"), UserHash.of(101L, "salt"));
    }

    @Test
    void saltIsPartOfTheHash() {
        // 这条是用来提醒"改盐即断链"的：同一个 userId 换盐后哈希完全不同，
        // 历史日志与新日志对不上，P2-H3 沉淀曲线无法按用户 join。
        assertNotEquals(UserHash.of(5L, "salt-a"), UserHash.of(5L, "salt-b"));
    }

    @Test
    void outputIs16HexChars() {
        String h = UserHash.of(123456L, "salt");

        assertEquals(16, h.length());
        assertFalse(h.matches(".*[^0-9a-f].*"), "非十六进制字符: " + h);
    }

    @Test
    void neverLeaksPlaintext() {
        // §2 明令"不用明文 userId"。裸 SHA-256 一个小整数是能被彩虹表单步反查的，
        // 所以这里连"哈希里能看出原文"这种低级形态也要挡住。
        String h = UserHash.of(100L, "salt");

        assertFalse(h.contains("100"));
    }

    @Test
    void nullSalt_treatedAsEmpty_notAsError() {
        // 配置缺失不该让留痕整条挂掉（§1）——按空盐算，宁可弱也不可炸
        assertEquals(16, UserHash.of(5L, null).length());
        assertEquals(UserHash.of(5L, ""), UserHash.of(5L, null));
    }
}
