package com.phoenix.demo.product.service.impl;

/**
 * 对外表达依赖故障，不暴露 Redis 地址或驱动异常内容。
 */
public class RedisUnavailableException extends RuntimeException {

    /**
     * 使用固定的安全错误描述。
     */
    public RedisUnavailableException() {
        super("商品查询依赖的 Redis 暂不可用");
    }
}
