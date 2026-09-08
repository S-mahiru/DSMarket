package com.dsmarket.modules.cart.dto;

import com.dsmarket.modules.product.dto.SpecItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CartItemVO {

    private Long id;
    private Long productId;
    private String productName;
    private String productImage;
    private Long skuId;
    private List<SpecItem> skuSpecs;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;
    private Integer stock;
    private Integer checked;
}
