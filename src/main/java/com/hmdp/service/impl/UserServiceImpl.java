package com.hmdp.service.impl;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号是否合法
        if(!RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不合法，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //3. 如果手机号合法，生成对应的验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 将验证码进行保存到 Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code, Duration.ofMinutes(LOGIN_CODE_TTL));
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号是否合法
        String phone = loginForm.getPhone();
        if(!RegexUtils.isPhoneInvalid(phone)){
            // 如果不合法，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        //2. 从Redis中拿到当前验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //3. 和用户输入的验证码进行校验，如果不一致，则无法通过校验
        if(cacheCode == null || !cacheCode.equals(loginForm.getCode())){
            return Result.fail("验证码不一致！");
        }
        //4. 如果一致，则后台根据手机号查询用户，如果用户不存在，则为用户创建账号信息，保存到数据库
        User user = query().eq("phone", phone).one();
        if(user == null){
            user = createUserWithPhone(phone);
            save(user);
        }
        //5. 无论是否存在，都会将用户信息保存到Redis中(HashMap存储)，方便后续获得当前登录信息（“登录态”存 Redis）
        //随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //6. 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //7. 设置token有效期
        stringRedisTemplate.expire(tokenKey,Duration.ofMinutes(LOGIN_USER_TTL));

        //8. 返回token
        return Result.ok(token);
    }

    /**
     * 根据手机号创建用户
     * @param phone 手机号
     * @return 用户
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        return user;
    }
}
