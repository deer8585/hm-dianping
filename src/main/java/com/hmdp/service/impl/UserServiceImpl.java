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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
     * 实现签到功能
     * @return
     */
    @Override
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    /**
     * 实现签到统计功能
     * @return
     */
    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();

        //5. 获得截止当前时间的最后一次签到数据
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()                                         //从第0位开始
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        //取出签到数据，因为只有get指令，所以result集合肯定只有一个值
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }

        //6. 循环遍历，并与1做与运算
        int count = 0;
        while (true){
            //让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if((num & 1) == 0){
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count ++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
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
