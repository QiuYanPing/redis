package com.qyp;

import com.qyp.jedis.JedisConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

public class JedisTest {
    private Jedis jedis;

    @BeforeEach
    void setUp() {
        //jedis = new Jedis("192.168.15.155", 6379);
        jedis = JedisConnectionFactory.getJedis();
        jedis.auth("123321");
        jedis.select(0);
    }

    @Test
    void testString() {
        String result = jedis.set("name","张三");
        System.out.println("result = " + result);

        String name = jedis.get("name");
        System.out.println("name = " + name);
    }

    @AfterEach
    void tearDown() {
        if (jedis != null){
            jedis.close();
        }
    }
}
