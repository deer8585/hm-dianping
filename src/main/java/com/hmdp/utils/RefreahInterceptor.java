package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Map;

/**
 * 全路径拦截器，刷新redis的token有效期
 */
public class RefreahInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    //使用构造方法注入bean
    public RefreahInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    //Controller 方法执行之前执行
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取请求头的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }

        //2. 基于TOKEN获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        //获取用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //如果用户不存在就放行
        if(userMap.isEmpty()){
            return true;
        }
        //3. 将查询到的hash数据转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //4. 存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //5. 刷新token有效期
        stringRedisTemplate.expire(key, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        //6. 放行
        return true;
    }

    @Override
    /**
     什么时候执行？
     请求完全结束后
     包括：
     Controller 执行完
     视图渲染完
     出现异常
     */
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
