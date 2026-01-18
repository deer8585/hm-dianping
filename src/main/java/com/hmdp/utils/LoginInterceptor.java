package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 登录拦截器
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    //Controller 方法执行之前执行
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 判断是否需要拦截（ThreadLocal中是否有用户）
        if(UserHolder.getUser() == null){
            //没有用户，拦截
            response.setStatus(401);
            return false;
        }
        //有用户,放行
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
