package com.dsmarket.modules.order.dto;

import com.dsmarket.modules.product.dto.SpecItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderItemVO {

    private Long productId;
    private Long skuId;
    private String productName;
    private String productImage;
    private List<SpecItem> skuSpecs;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;
}
