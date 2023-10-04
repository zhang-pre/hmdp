package com.hmdp;
// 获取1000个Token测试代码

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.impl.UserServiceImpl;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


import org.junit.jupiter.api.Test;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;


@SpringBootTest

public class RedisCreateToken {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userService;

    @Test
    public void test() {
        FileWriter fr = null;
        try {
            fr = new FileWriter("C:\\Users\\Administrator\\Downloads\\tokens.txt");
            LambdaQueryWrapper<User> lambdaQueryWrapper = new LambdaQueryWrapper<>();
            lambdaQueryWrapper.last("limit 100");
            List<User> list = userService.list(lambdaQueryWrapper);
            System.out.println(list.size());
            for (User user : list) {
                String token = UUID.randomUUID().toString(true);
                fr.append(token);
                fr.append("\n");
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String,String> userMap = new HashMap<>();
                userMap.put("id",userDTO.getId().toString());
                userMap.put("nickName",userDTO.getNickName());
                userMap.put("icon",userDTO.getIcon());
                String tokenKey = token;
                stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
                stringRedisTemplate.expire(tokenKey,2440, TimeUnit.MINUTES);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                fr.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

}