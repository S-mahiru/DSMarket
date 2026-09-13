package com.dsmarket.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.product.dto.CategoryProductCount;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.support.ProductVisibility;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 按分类聚合「公开可见」商品数（REQ-20260913 §4.9 落点 4）。
     *
     * <p>判据直接复用 {@link ProductVisibility#SQL} —— 这正是该判据做成常量而非私有方法的原因：
     * 这里是 {@code @Select} 注解里的编译期常量拼接，Java 方法够不着。</p>
     *
     * <p><b>两处必须手写、不能省</b>：{@code deleted = 0}，以及判据里已自带的 {@code status = 1} ——
     * {@code @TableLogic} 只作用于 MyBatis-Plus 自己生成的语句，管不到本注解里的原生 SQL（§7.2）。</p>
     *
     * <p>聚合粒度是**直挂分类**，不含子分类；子树求和在装配侧递归完成。</p>
     */
    @Select("SELECT category_id, COUNT(*) AS cnt FROM dsm_product "
            + "WHERE deleted = 0 AND " + ProductVisibility.SQL + " GROUP BY category_id")
    List<CategoryProductCount> countPublicVisibleGroupByCategory();

    /** 原子条件扣减商品库存（防超卖）：stock >= quantity 才扣减 */
    @Update("UPDATE dsm_product SET stock = stock - #{quantity} " +
            "WHERE id = #{productId} AND stock >= #{quantity} AND deleted = 0")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /** 无条件同步扣减商品总库存（SKU 商品聚合字段，实际可用库存以 SKU 为准） */
    @Update("UPDATE dsm_product SET stock = stock - #{quantity} " +
            "WHERE id = #{productId} AND deleted = 0")
    int decrementStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /** 恢复商品库存 */
    @Update("UPDATE dsm_product SET stock = stock + #{quantity} " +
            "WHERE id = #{productId} AND deleted = 0")
    int incrementStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /** 累加销量 */
    @Update("UPDATE dsm_product SET sales = sales + #{quantity} " +
            "WHERE id = #{productId} AND deleted = 0")
    int incrementSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
