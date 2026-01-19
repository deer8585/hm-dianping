package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息 (使用redis缓存)
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {

        // 1. 缓存穿透解决方案（缓存空值）
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                Duration.ofMinutes(CACHE_SHOP_TTL)
        );

        // 2. 互斥锁解决缓存击穿（可选）
        // Shop shop = cacheClient.queryWithMutex(
        //         CACHE_SHOP_KEY,
        //         id,
        //         Shop.class,
        //         this::getById,
        //         Duration.ofMinutes(CACHE_SHOP_TTL)
        // );

        // 3. 逻辑过期解决缓存击穿（热点 key 推荐）
        // Shop shop = cacheClient.queryWithLogicalExpire(
        //         CACHE_SHOP_KEY,
        //         id,
        //         Shop.class,
        //         this::getById,
        //         Duration.ofSeconds(20)
        // );

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        return Result.ok(shop);
    }

    /*@Override
    public Result queryById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1. 从redis查询店铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3. 存在就直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        //命中的是空值（字符串shopJson不为空则存储的是null值）
        if(shopJson != null){
            return Result.fail("店铺信息不存在！");
        }
        //4. 不存在就根据id查询数据库
        Shop shop = getById(id);
        //5. 数据库也不存在则缓存null值
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"", Duration.ofMinutes(CACHE_NULL_TTL));
        }
        //6. 存在，写入redis(采用字符串的形式)
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), Duration.ofMinutes(CACHE_SHOP_TTL));

        //7. 返回
        return Result.ok(shop);
    }*/



    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop == null){
            return Result.fail("商铺为空!");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不存在！");
        }
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
