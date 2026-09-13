package com.dsmarket.modules.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.modules.product.entity.Product;

/**
 * 商品「公开可见」判据的**唯一事实来源**（REQ-20260913 §4.8）。
 *
 * <p>定义：{@code dsm_product.status = 1} <b>且</b>（{@code shop_id IS NULL}（平台自营）
 * <b>或</b> 所属店铺 {@code status = 1} 且未软删）。</p>
 *
 * <p><b>为什么是常量而不是私有方法</b>：该判据有六个落点，分属五个类 ——
 * {@code ProductSearchServiceImpl.doSearch}（T3）、{@code ProductServiceImpl.getFeatured}（T4）、
 * {@code ProductServiceImpl.getDetail}（T5）、{@code ProductMapper} 的分类计数原生 SQL（T6），
 * 以及**写路径**的两处 {@code CartServiceImpl.add} / {@code OrderServiceImpl.create}
 * ——后两者经 {@link ProductPurchaseGuard} 间接复用，见 §12 Q6。
 * 前三个吃 {@link LambdaQueryWrapper}，第四个是 {@code @Select} 注解里的拼接字符串，
 * 方法够不着。**常量是唯一能同时喂给两者的形态**，据此满足 §4.8「不得拷三份」。
 * 改动此常量即同时改变六处口径 —— 读侧看不看得见、写侧买不买得成，**同进同退**。</p>
 *
 * <p><b>两处必须手写、不能省</b>：① {@code dsm_shop.deleted = 0} —— 子查询是原生 SQL，
 * {@code @TableLogic} 只作用于 MyBatis-Plus 自己生成的语句（§7.2）；
 * ② 整个谓词用括号包住 —— 它要与其他条件 AND 拼接，少一层括号会让 {@code OR} 逃逸，
 * 把别的筛选条件整个吃掉。</p>
 *
 * <p><b>使用边界</b>：只可用于<b>商城侧</b>路径。管理端 {@code searchAllStatus}、
 * 商家侧 {@code searchByShop} 都<b>不得</b>使用 —— 商家必须看得到自己已下架的商品，
 * 管理端必须看得到全平台商品。因该常量内含 {@code status = 1}，
 * 误用会把这两条路径静默地"只留上架"，且后台不会报错。
 * 反过来，**写路径（加购 / 下单）属商城侧，用它是正确的** —— 买家只应买得成前台上看得见的东西。</p>
 */
public final class ProductVisibility {

    /**
     * 公开可见判据的 SQL 片段。已自带最外层括号，可直接 AND 进任意 WHERE。
     *
     * <p>表名写死 {@code dsm_product} / {@code dsm_shop}：MyBatis-Plus 生成的语句不加别名，
     * 四个落点共用同一组表名。</p>
     */
    public static final String SQL =
            "(dsm_product.status = 1 AND (dsm_product.shop_id IS NULL OR EXISTS ("
                    + "SELECT 1 FROM dsm_shop s WHERE s.id = dsm_product.shop_id"
                    + " AND s.status = 1 AND s.deleted = 0)))";

    private ProductVisibility() {
    }

    /**
     * 把公开可见判据 AND 进商品查询条件。
     *
     * <p>调用点须自行保证处于商城侧语义下（见类注释「使用边界」）。</p>
     */
    public static void apply(LambdaQueryWrapper<Product> wrapper) {
        wrapper.apply(SQL);
    }
}
