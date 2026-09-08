package com.dsmarket.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.product.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductMapper extends BaseMapper<Product> {

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
