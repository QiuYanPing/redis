package com.hmdp;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Test
    public void redisTest(){
        stringRedisTemplate.opsForValue().set("key1","value1");
    }


}
