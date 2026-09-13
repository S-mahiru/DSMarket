package com.dsmarket.modules.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.ShopStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 商品「当前可购买」守门人 —— **写路径**（加购 / 下单）的唯一判定点。
 *
 * <p><b>为什么需要它</b>（{@code REQ-20260913-店铺关闭能力} §12 Q6，2026-09-13 拍板「甲」）：
 * 读侧（列表 / 首页推荐 / 详情 / 店铺页 / 分类计数）在店铺关闭后已让商品全站消失，
 * 但写侧完全不看店铺状态 —— 结果是「平台对用户说了三遍这商品没了，第四次却收下了钱」。
 * 真机实测确认：关店后**仍能下单**（订单落库 ¥188），且**关店后还能重新加购**。
 * 本类补的就是这半场。</p>
 *
 * <p><b>判定只有一处</b>：直接复用 {@link ProductVisibility#SQL} —— 与读侧同一份 SQL 常量，
 * 而不是在这里用 Java 把「店铺是否开通」重写一遍。若重写，两份判据迟早分叉，
 * 那正是 §4.8「不得拷三份」要防的事。故**改动可见性语义只需改那个常量**，本类自动跟随。</p>
 *
 * <p><b>自营商品不受影响</b>：{@code shop_id IS NULL} 在判据里走的是短路分支，
 * 平台自营商品照常可买 —— 这是本类最容易误伤的一处。</p>
 *
 * <p><b>为什么落到 400 而不是 409</b>：与同一条下单循环里相邻的
 * 「商品已下架或不存在」「商品库存不足」保持同码。对买家而言这三件事是同一类
 * ——「这一行买不了」，而不是「你的请求与资源当前状态冲突、你改一下再来」。</p>
 *
 * <p><b>边界</b>：只管「能不能买」。不负责把已关闭店铺的商品从**已有购物车**里清掉或标记
 * —— 购物车列表仍会显示它，但结账时会被本类拦下并给出明确文案。</p>
 */
@Component
@RequiredArgsConstructor
public class ProductPurchaseGuard {

    private final ProductMapper productMapper;
    private final ShopMapper shopMapper;

    /**
     * 校验商品当前可购买，可购买则返回商品实体。
     *
     * <p>返回实体而非 {@code void}：调用方（下单）本来就要用商品的名字 / 图片 / 价格，
     * 让它复用本次查询的结果，省掉一次 {@code selectById}。</p>
     *
     * @throws BusinessException 商品不存在、已下架，或所属店铺非「已开通」
     */
    public Product requirePurchasable(Long productId) {
        Product product = selectPubliclyVisible(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), describeUnavailable(productId));
        }
        return product;
    }

    /**
     * 按 id 查「公开可见」的商品；不可见时返回 {@code null}。
     *
     * <p>判定交给 {@link ProductVisibility}，本方法只负责把 id 条件拼上去。</p>
     */
    private Product selectPubliclyVisible(Long productId) {
        if (productId == null) {
            return null;
        }
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getId, productId);
        ProductVisibility.apply(wrapper);
        return productMapper.selectOne(wrapper);
    }

    /**
     * 决定「不可购买」时给用户看的文案。
     *
     * <p><b>本方法不参与判定</b> —— 能不能买已经由 {@link #selectPubliclyVisible} 用唯一判据定完了，
     * 这里再查一次**只是为了把话说清楚**。它在任何输入下都不可能让一个已判为不可见的商品变得可见，
     * 因为它的返回值只被塞进异常消息。</p>
     *
     * <p>店铺侧的文案不区分 0/1/2/3 的具体状态，一律说「已关闭」：这与 §8.3 让
     * 「不存在」与「已关闭」不可区分的处置同源 —— 买家只需要知道「这店现在不卖东西」，
     * 区分待审核 / 已驳回 / 已关闭只会把平台内部状态暴露出去。</p>
     */
    private String describeUnavailable(Long productId) {
        Product raw = productId == null ? null : productMapper.selectById(productId);
        if (raw == null || !Integer.valueOf(1).equals(raw.getStatus())) {
            return "商品已下架或不存在";
        }
        if (raw.getShopId() != null) {
            Shop shop = shopMapper.selectById(raw.getShopId());
            if (shop == null || !ShopStatusEnum.OPEN.is(shop.getStatus())) {
                return "商品所属店铺已关闭: " + raw.getName();
            }
        }
        // 走到这里意味着「商品在售、店铺开通，却在唯一判据下落选」—— 理论上不可达。
        // 不猜原因，回落到通用文案：将来判据新增条件时，这里也不会说出与事实相反的提示。
        return "商品已下架或不存在";
    }
}
