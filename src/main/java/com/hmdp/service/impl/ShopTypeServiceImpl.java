package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询 shop_type 表中的所有数据 (使用redis缓存)
     * @return
     */
    @Override
    public Result getTypeList() {
        String key = "cache:shoptype:list";
        //1. 先查询redis
        String shopTypeJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOPTYPE_KEY);
        if(StrUtil.isNotBlank(shopTypeJson)){
            //2. 非空则反序列化回结果
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        //3. redis 没有就查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if(shopTypeList == null || shopTypeList.size() == 0){
            return Result.fail("没有店铺类型 !");
        }

        //4. 查到存入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList));

        //5. 返回
        return Result.ok(shopTypeList);
    }
}
