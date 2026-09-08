package com.dsmarket.modules.product.dto;

import com.dsmarket.modules.product.entity.ProductSku;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductSkuVO {

    private Long id;
    private String skuCode;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private List<SpecItem> specs;
    private String image;
    private Integer sortOrder;

    public static ProductSkuVO from(ProductSku sku, List<SpecItem> specs) {
        ProductSkuVO vo = new ProductSkuVO();
        vo.setId(sku.getId());
        vo.setSkuCode(sku.getSkuCode());
        vo.setPrice(sku.getPrice());
        vo.setOriginalPrice(sku.getOriginalPrice());
        vo.setStock(sku.getStock());
        vo.setSpecs(specs);
        vo.setImage(sku.getImage());
        vo.setSortOrder(sku.getSortOrder());
        return vo;
    }
}
