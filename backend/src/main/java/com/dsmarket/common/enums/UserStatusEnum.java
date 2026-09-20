package com.dsmarket.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 账号状态。`dsm_user.status` 的取值全集，**唯一事实来源** —— 不要再在别处写裸的 0/1。
 *
 * <p><b>为什么值得有个枚举</b>：这个判据有两个消费点，漏掉任一个的后果不一样 ——</p>
 *
 * <ul>
 *   <li>{@code AuthServiceImpl#login}：漏了 = 被禁用的账号还能<b>登进来</b>；</li>
 *   <li>{@code JwtAuthenticationFilter}：漏了 = 已被禁用的账号手里那张 token 在剩余有效期
 *       （{@code jwt.expiration}，默认 24h）内<b>照常可用</b>（审计 12-readiness-audit §1.1 / §2.5）。</li>
 * </ul>
 *
 * <p>第二种的具体形态是"权限检查全都对、但那个人的 token 就是还能用"，从日志里看不出来。
 * 两处各写一个裸 {@code 1}，改口径时漏一处漏出来的正是它。故与 {@link ShopStatusEnum}
 * 同一副纪律：判据只留一个事实来源。</p>
 *
 * <p>取值范围由 {@code UserServiceImpl#adminUpdateStatus} 约束（只接受 0 / 1）。</p>
 *
 * <p><b>与 {@link ShopStatusEnum} 的差异是刻意的</b>：这里只有两个取值，且目前没有任何
 * "状态展示名"的消费点（{@code UserAdminVO} 直接把 {@code status} 原样给出），
 * 故不带 {@code displayName} / {@code nameOf} / {@code of}。等真有消费点再加，不预先造。</p>
 */
@Getter
@AllArgsConstructor
public enum UserStatusEnum {

    /** 已禁用：拒绝登录，且既有 token 立即失效。 */
    DISABLED(0),
    /** 正常：允许登录，已签发的 token 有效。 */
    ENABLED(1);

    private final Integer value;

    /**
     * 该状态是否等于给定取值。
     * 与 {@link ShopStatusEnum#is(Integer)} 同形 —— 比在调用处写 {@code Integer.valueOf(1).equals(...)}
     * 少一次装箱，且对 {@code null} 天然返回 {@code false}（调用处不必先判空）。
     */
    public boolean is(Integer status) {
        return this.value.equals(status);
    }
}
