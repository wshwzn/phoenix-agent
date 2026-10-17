package com.phoenix.demo.product.dto;

import java.math.BigDecimal;

/**
 * 固定演示商品，名称优先读取 Redis，未初始化数据时使用内置名称。
 */
public record ProductResponse(long id, String name, BigDecimal price) {
}
