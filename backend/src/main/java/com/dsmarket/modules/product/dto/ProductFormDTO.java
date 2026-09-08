package com.dsmarket.modules.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductFormDTO {

    private Long id;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String title;
    private String brief;
    private String description;

    @NotNull(message = "请选择分类")
    private Long categoryId;

    private String brand;
    private String unit;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价需大于0")
    private BigDecimal price;

    private BigDecimal originalPrice;

    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    private String mainImage;
    private List<String> subImages = new ArrayList<>();
    private List<String> detailImages = new ArrayList<>();
    private Integer hasSku;
    private Integer isFeatured;
    private BigDecimal weight;
    private Integer status;
    private Integer sortOrder;
    private List<ProductSkuFormDTO> skus = new ArrayList<>();
}
