package com.dsmarket.modules.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dsmarket.modules.product.entity.ProductSku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    /** 原子条件扣减 SKU 库存（防超卖核心） */
    @Update("UPDATE dsm_product_sku SET stock = stock - #{quantity} " +
            "WHERE id = #{skuId} AND stock >= #{quantity} AND deleted = 0")
    int deductStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);

    /** 恢复 SKU 库存 */
    @Update("UPDATE dsm_product_sku SET stock = stock + #{quantity} " +
            "WHERE id = #{skuId} AND deleted = 0")
    int incrementStock(@Param("skuId") Long skuId, @Param("quantity") Integer quantity);
}
