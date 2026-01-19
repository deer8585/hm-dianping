package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设置逻辑过期时间的类
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
