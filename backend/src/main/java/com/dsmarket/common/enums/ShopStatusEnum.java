package com.dsmarket.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 店铺状态。`dsm_shop.status` 的取值全集，**唯一事实来源** —— 不要再在别处写裸的 0/1/2/3。
 *
 * <p>状态机（REQ-20260913-店铺关闭能力 §6.2）：
 *
 * <pre>
 *   （无）   --商家申请-->      0 待审核
 *   0 待审核 --管理员通过-->    1 已开通
 *   0 待审核 --管理员驳回-->    2 已驳回
 *   2 已驳回 --商家重新申请-->  0 待审核
 *   1 已开通 --管理员关闭-->    3 已关闭   （终局：不可重开，也不可重新申请）
 * </pre>
 *
 * <p><b>2 与 3 必须分开，这是本枚举存在的全部理由。</b>
 * {@code 2} 的代码语义是「申请被驳回，可以改资料重来」——{@code ShopServiceImpl#apply}
 * 对 2 **放行**，重新提交会把店铺覆盖并重置为 0。{@code 3} 是「开通后被关闭」，终局。
 * 曾经把 2 同时在文档里当作「已驳回/关闭」用，结果是"关闭"形同虚设：被关闭者只要
 * 重新提交一次申请就复活了。方案甲的价值就在于**不碰 2**。
 */
@Getter
@AllArgsConstructor
public enum ShopStatusEnum {

    /** 待审核：商家已提交入驻申请，等待管理员审核 */
    PENDING(0, "待审核"),
    /** 已开通：审核通过，商家获得商家身份并可经营 */
    OPEN(1, "已开通"),
    /** 已驳回：申请没过。**可修改资料后重新提交**（{@code apply} 对此状态放行） */
    REJECTED(2, "已驳回"),
    /** 已关闭：开通后被管理员关闭。**终局** —— 不可重开，也不可重新申请 */
    CLOSED(3, "已关闭");

    private final Integer value;
    private final String displayName;

    /** 状态取值为空或超出已知集合时的兜底文案（历史脏数据 / 未来新增值） */
    public static final String UNKNOWN_NAME = "未知";

    /** 按取值查枚举；未匹配返回 {@code null}（不抛异常——展示层不该因为脏数据炸掉） */
    public static ShopStatusEnum of(Integer status) {
        for (ShopStatusEnum e : values()) {
            if (e.value.equals(status)) {
                return e;
            }
        }
        return null;
    }

    /** 展示名。**这是 status 到中文的唯一映射**，{@code ShopVO.statusName} 直接委托给它。 */
    public static String nameOf(Integer status) {
        ShopStatusEnum e = of(status);
        return e == null ? UNKNOWN_NAME : e.displayName;
    }

    /** 该状态是否等于给定取值。比在调用处写 {@code Integer.valueOf(1).equals(...)} 少一次装箱。 */
    public boolean is(Integer status) {
        return this.value.equals(status);
    }
}
