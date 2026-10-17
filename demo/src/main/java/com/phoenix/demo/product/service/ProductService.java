package com.phoenix.demo.product.service;

import com.phoenix.demo.product.dto.ProductResponse;

/**
 * 演示依赖 Redis 的商品查询。
 */
public interface ProductService {

    /**
     * 查询唯一的演示商品，依赖不可用时不掩盖故障。
     */
    ProductResponse findDemoProduct();
}
