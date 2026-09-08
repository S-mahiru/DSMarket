package com.dsmarket.modules.category.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_category")
public class Category extends BaseEntity {

    private String name;
    private Long parentId;
    private Integer level;
    private Integer sortOrder;
    private String icon;
    private String image;
    private Integer status;
}
