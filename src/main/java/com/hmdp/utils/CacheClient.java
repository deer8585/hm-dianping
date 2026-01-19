package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //创建线程池
    //用于：
    //👉 逻辑过期后 异步重建缓存
    //避免阻塞当前请求线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //构造方法注入StringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     *  普通缓存
     */
    public void set(String key, Object value, Duration duration) {
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), duration);
    }


    /**
     *  逻辑缓存
     */
    public void setWithLogicalExpire(String key, Object value, Duration duration) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(duration.getSeconds()));
        // 写入Redis
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     *穿透查询(缓存空值 + TTL)
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Duration duration) {

        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue()
                    .set(key, "", Duration.ofMinutes(CACHE_NULL_TTL));

            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, duration);
        return r;
    }

    /**
     *逻辑过期查询
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Duration duration) {

        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, duration);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    /**
     *互斥锁查询
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Duration duration) {

        String key = keyPrefix + id;

        // 1. 查询 Redis
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中有效缓存
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 3. 命中缓存空值（防穿透）
        if (json != null) {
            return null;
        }

        // 4. Redis 未命中，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r;

        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取锁失败，短暂休眠后重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, duration);
            }

            // 5. 获取锁成功，查询数据库
            r = dbFallback.apply(id);

            // 6. 数据库不存在，缓存空值
            if (r == null) {
                stringRedisTemplate.opsForValue()
                        .set(key, "", Duration.ofMinutes(CACHE_NULL_TTL));
                return null;
            }

            // 7. 数据库存在，写入缓存
            this.set(key, r, duration);

        } catch (InterruptedException e) {
//            作用：重新设置当前线程的“中断标志位”，
//            告诉上层代码：这个线程曾经被中断过。
            //Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            // 8. 释放锁
            unlock(lockKey);
        }

        return r;
    }


    /**
     * 加锁
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(10));
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}