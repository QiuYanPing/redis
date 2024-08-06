package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
                                          Long time, TimeUnit unit) {
        //1.从redis缓存中查找店铺
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.存在，返回店铺信息
        if (StrUtil.isNotBlank(json)) {
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        if (json != null) {
            //存在，但为空值
            return null;
        }
        //3.不存在，根据id查询数据库
        R r = dbFallBack.apply(id);
        //4.判断是否为空
        if (r == null) {
            //5.不存在，返回null，并在redis中置空值
            set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis缓存并返回店铺信息
        set(key, r, time, unit);
        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
                                             Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，直接返回null
            return null;
        }

        //4.命中，需要先打json反序列化为java对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2过期，重建缓存
        //6.重建缓存
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock) {
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R apply = dbFallBack.apply(id);
                    //写入redis
                    setWithLogicalExpire(key, r, time, unit);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //6.4返回店铺信息
        return r;
    }

    private boolean tryLock(String lockKey) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//不能返回，拆箱过程可能产生空指针 flag!=null && flag

    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    private <R, ID> R queryWithMetux(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
                                     Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回店铺信息
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //判断命中是否为空值
        if (json != null) {
            //返回错误信息
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if (!isLock) {
                //4.3失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMetux(keyPrefix, id, type, dbFallBack, time, unit);
            }
            //4.4成功，根据id查询数据库
            r = dbFallBack.apply(id);
            //模拟重建的延迟
            Thread.sleep(200);
            //5.不存在，返回错误信息
            if (r == null) {
                //将空值写入redis
                set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，将数据写入redis缓存中
            set(key,r,time,unit);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //7.释放互斥锁
            unLock(lockKey);
        }
        //8.返回店铺信息
        return r;
    }


}
