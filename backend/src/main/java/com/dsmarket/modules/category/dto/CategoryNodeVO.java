package com.dsmarket.modules.category.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryNodeVO {

    private Long id;
    private String name;
    private Integer level;
    private List<CategoryNodeVO> children = new ArrayList<>();
}
