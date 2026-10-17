package com.phoenix.demo.product.controller;

import com.phoenix.demo.product.dto.ProductResponse;
import com.phoenix.demo.product.service.ProductService;
import com.phoenix.demo.product.service.impl.RedisUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 只暴露固定商品的只读查询，用于复现依赖不可用场景。
 */
@RestController
@RequestMapping("/api/demo/products")
public class ProductController {

    private final ProductService service;

    /**
     * 注入商品查询服务。
     */
    public ProductController(ProductService service) {
        this.service = service;
    }

    /**
     * 查询演示商品，不创建或修改 Redis 数据。
     */
    @GetMapping("/1")
    public ProductResponse getDemoProduct() {
        return service.findDemoProduct();
    }

    /**
     * 将依赖异常映射为可采集的 503 与固定错误代码。
     */
    @ExceptionHandler(RedisUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleRedisUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "REDIS_UNAVAILABLE", "message", "商品查询依赖的 Redis 暂不可用"));
    }
}
