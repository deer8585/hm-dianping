package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
   private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 首页展示热门博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLike(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据ID查询博客详情页
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //1. 查询博客
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在！");
        }

        //2. 查询blog有关用户
        queryBlogUser(blog);
        //3. 查询blog是否被点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    /**
     * 查询blog是否被点赞
     * @param blog
     */
    private void isBlogLike(Blog blog) {
        //1. 获取登录当前用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //用户未登录，返回
            return;
        }
        Long userId = user.getId();
        //2. 判断当前用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 用户点赞功能
     * @param id 博客id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1. 获取登录当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 判断当前用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //3. 如果未点赞，可以点赞,数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //保存用户到Redis集合,用时间戳作为分数
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4. 如果已经点赞，则取消点赞,数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                //把用户从Redis中移除
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞列表查询
     * @param id 博客id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1. 查询top5的点赞用户 zrange key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //2. 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        //3. 根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTO = userService.query()
                .in("id", ids)
                .last("order by field(id" + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //返回
        return Result.ok(userDTO);
    }

    /**
     * 发布博客并推送到粉丝收件箱
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1.  获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        //2. 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        //3. 查询笔记作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        //4. 发送笔记给所有粉丝
        for (Follow follow : follows) {
            //获取粉丝Id
            Long userId1 = follow.getUserId();
            //推送
            String key = RedisConstants.FEED_KEY + userId1;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回博客id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询收邮箱
     * @param max 上一次分页查询的最后id
     * @param offset 偏移量（与上一次查询相同的查询个数）
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3. 非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4. 解析数据：blogId,minTime（时间戳）,offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; //初始化为1，即有一个为它本身
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取博客id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if(minTime == time){
                os ++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //5. 根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("order by field (id," + idStr + ")").list();

        for (Blog blog : blogs) {
            //2. 查询blog有关用户
            queryBlogUser(blog);
            //3. 查询blog是否被点赞
            isBlogLike(blog);
        }

        //6. 封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);

        //下次分页的 offset
        scrollResult.setOffset(os);
        //
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    /**
     * 查询用户
     * @param blog
     */
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
}
