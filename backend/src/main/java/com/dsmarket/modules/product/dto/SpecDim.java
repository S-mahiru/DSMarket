package com.dsmarket.modules.product.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SpecDim {

    private String key;
    private List<String> values = new ArrayList<>();
}
