package com.phoenix.demo.product.service.impl;

import com.phoenix.demo.product.dto.ProductResponse;
import com.phoenix.demo.product.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 每次查询实际读取 Redis；专用日志仅记录固定事件，不附带异常原文或凭据。
 */
@Service
public class ProductServiceImpl implements ProductService {

    private static final Logger diagnostic = LoggerFactory.getLogger("phoenix.demo.diagnostic");
    private final StringRedisTemplate redisTemplate;

    /**
     * 注入 Spring 管理的 Redis 客户端。
     */
    public ProductServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取商品名称，记录可与接口结果对应的成功或失败证据。
     */
    @Override
    public ProductResponse findDemoProduct() {
        try {
            String name = redisTemplate.opsForValue().get("phoenix:demo:product:1:name");
            diagnostic.info("event=PRODUCT_QUERY_OK application=phoenix-demo dependency=redis productId=1");
            return new ProductResponse(1, name == null ? "Phoenix 演示商品" : name, new BigDecimal("99.00"));
        } catch (DataAccessException exception) {
            diagnostic.warn("event=PRODUCT_QUERY_FAILED application=phoenix-demo dependency=redis code=REDIS_UNAVAILABLE productId=1");
            throw new RedisUnavailableException();
        }
    }
}
