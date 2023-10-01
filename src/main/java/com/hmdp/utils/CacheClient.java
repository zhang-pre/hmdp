package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


@Slf4j
@Component
public class CacheClient {  //基于StringRedisTemplate封装一个缓存工具类
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key,Object value ,Long time ,TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,unit);
    }
    public <T> void setWithLogicExpire(String key,T value,Long time,TimeUnit unit){
        RedisData<T> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //缓存穿透
    public <R,ID> R cachePenetration(String prefix, ID id, Class<R> type,
                                     Function<ID,R> dbFallback,Long time ,TimeUnit unit){
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        // 判断是否是""
        if(json!=null){
            return null;
        }
        //未命中
        R r = dbFallback.apply(id);
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",2L,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }
    //利用逻辑过期解决缓存击穿
    public <R,ID> R cacheBreakdownWithLogicExpire(String prefix, String lockPrefix,ID id, Class<R> type,
                                           Function<ID,R> dbFallback,Long time ,TimeUnit unit){ //缓存击穿,使用逻辑过期来解决
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        //缓存命中
        TypeReference<RedisData<R>> typeReference = new TypeReference<RedisData<R>>() {};
        RedisData<R> redisData = JSONUtil.toBean(json,typeReference,false);
        R r = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return r;
        }
        String LockKey = lockPrefix + id;
        try{
            boolean isLock = tryLock(LockKey);
            if(!isLock){//获取锁失败
                return r;
            }
            //获取锁成功
            //这里获取锁成功后，应该再次检查redis缓存字段是否过期，做DoubleCheck。
            //如果存在则无需开启独立线程新建缓存
            json = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isBlank(json)){
                return null;
            }
            redisData = JSONUtil.toBean(json,typeReference,false);
            r = redisData.getData();
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期
                return r;
            }

            // DoubleCheck后，redis缓存中，还是过期了，则重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //设置20秒，以便查询
                //查询数据库
                R r1 = dbFallback.apply(id);
                //写入redis
                this.setWithLogicExpire(key,r1,time,unit);
                log.debug("成功打入redis中");
            });
        }catch (Exception e){
            log.debug("发生异常");
            throw new RuntimeException(e);
        }
        finally {
            unlock(LockKey);
        }
        log.debug("执行到这里了吗");
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
