package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注取关功能
     * @param followId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followId, Boolean isFollow) {
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //2. 判断是否关注
        if(isFollow){
            //关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key,followId.toString());
            }
        }else {
            //取关，删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().
                    eq("user_id", userId).eq("follow_user_id", followId));
            if(isSuccess){
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key,followId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看是否关注
     * @param followId
     * @return
     */
    @Override
    public Result isFollow(Long followId) {
        Long userId = UserHolder.getUser().getId();
        //到数据库查询
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followId).count();
        return Result.ok(count > 0);
    }

    /**
     * 查看共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        //1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        //2. 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok();
        }
        //3. 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4. 查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
