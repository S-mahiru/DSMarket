package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;

import java.util.List;

public interface ProductService {

    PageResult<ProductListVO> page(int page, int size, ProductQuery query);

    PageResult<ProductListVO> adminPage(int page, int size, ProductQuery query);

    List<ProductListVO> getFeatured(int limit);

    ProductDetailVO getDetail(Long id);

    ProductDetailVO getAdminDetail(Long id);

    Long create(ProductFormDTO form);

    void update(Long id, ProductFormDTO form);

    void updateStatus(Long id, Integer status);

    void delete(Long id);
}
