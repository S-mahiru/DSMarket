package com.dsmarket.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色。**单值** —— `dsm_user.role` 存的是该账号的**最高身份**，不是"拥有的角色集合"
 * （REQ-20260912 §3）。
 *
 * <p>能力继承关系（单向、向下包含）：
 *
 * <pre>
 *   ADMIN ⊃ MERCHANT ⊃ USER
 * </pre>
 *
 * <p>由此推出三条写代码时必须遵守的规则：
 *
 * <ol>
 *   <li>高身份**自动继承**低身份的全部能力 —— ADMIN 能购物、MERCHANT 能购物。
 *       <b>没有</b>"仅商家不可购物"这类限制，这与淘宝/京东"开店后同账号仍可购物"一致。
 *   <li>判断"是不是买家"**不要写 {@code USER.getValue().equals(role)}** —— 单值相等会把
 *       MERCHANT / ADMIN 判成"非买家"。买家面（购物车、订单、地址、个人中心）只校验
 *       "已登录"，不校验角色，靠的就是上面这条继承。
 *   <li>只有判断"是不是商家/管理员"才可以用精确匹配（单值语义在这两个上成立）。
 * </ol>
 *
 * <p>升级路径：注册即 {@code USER}；入驻审核通过时由
 * {@code ShopServiceImpl#adminAudit} 置为 {@code MERCHANT}。驳回**不改变**角色 ——
 * 被驳回者仍是 {@code USER}，因此还能重新提交入驻申请。{@code ADMIN} 无自助升级入口，
 * 只能由数据库/运维直接设置。
 */
@Getter
@AllArgsConstructor
public enum UserRoleEnum {

    /** 普通用户：完整购物流程 */
    USER("USER", "普通用户"),
    /** 商家：购物能力 + 管理自家店铺的商品（数据按 shop_id 隔离） */
    MERCHANT("MERCHANT", "商家"),
    /** 管理员：全平台管理（含商家商品）；**不**共用商家入口，见 REQ-20260912 §4.7 */
    ADMIN("ADMIN", "管理员");

    private final String value;
    private final String displayName;
}
