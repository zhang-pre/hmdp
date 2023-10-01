package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
//        Shop shop = cacheClient.cachePenetration("cache:shop", id, Shop.class, (id1) -> {
//            return getById(id1);
//        }, 30L, TimeUnit.MINUTES);
        //RedisData中使用了泛型，导致这里不认识，只能使用Object了
        Object o  = cacheClient.cacheBreakdownWithLogicExpire("cache:shop", "lock:shop",
                id, Shop.class, (id1) -> {
                    return getById(id1);
                }, 30L, TimeUnit.MINUTES);
//        Shop shop = cacheBreakdownWithLogicExpire(id);
        if(o==null){
            return Result.fail("商铺不存在");
        }
        return Result.ok(o);
    }
    Shop cacheBreakdownWithMutex(Long id){ //缓存击穿,使用互斥来解决
        String key = "cache:shop" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        // 判断是否是""
        if(shopJson!=null){
            return null;
        }
        //缓存未命中
        String LockKey = "lock:shop" + id;
        Shop shop = null;
        try{
            boolean isLock = tryLock(LockKey);
            if(!isLock){//获取锁失败
                Thread.sleep(50);
                return cacheBreakdownWithMutex(id);
            }
            //获取锁成功
            //这里获取锁成功后，应该再次检查redis缓存是否存在，做DoubleCheck。
            //如果存在则无需重建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson,Shop.class);
            }
            // 判断是否是""
            if(shopJson!=null){
                return null;
            }
            // DoubleCheck后，redis缓存还是不存在，则重建缓存
            shop = getBaseMapper().selectById(id);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",2L,TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            unlock(LockKey);
        }
        return shop;
    }
    public void saveShop2Redis(Long id,Long expireSeconds){
        Shop shop = getById(id);

        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set("cache:shop"+id,JSONUtil.toJsonStr(redisData));
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    Shop cacheBreakdownWithLogicExpire(Long id){ //缓存击穿,使用逻辑过期来解决
        String key = "cache:shop" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //缓存命中
        TypeReference<RedisData<Shop>> typeReference = new TypeReference<RedisData<Shop>>() {};
        RedisData<Shop> redisData = JSONUtil.toBean(shopJson,typeReference,false);
        Shop shop = redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return shop;
        }
        String LockKey = "lock:shop" + id;
        try{
            boolean isLock = tryLock(LockKey);
            if(!isLock){//获取锁失败
                return shop;
            }
            //获取锁成功
            //这里获取锁成功后，应该再次检查redis缓存字段是否过期，做DoubleCheck。
            //如果存在则无需开启独立线程新建缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isBlank(shopJson)){
                return null;
            }
            redisData = JSONUtil.toBean(shopJson,typeReference,false);
            shop = redisData.getData();
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期
                return shop;
            }

            // DoubleCheck后，redis缓存中，还是过期了，则重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //设置20秒，以便查询
               saveShop2Redis(id,20L);
            });
        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            unlock(LockKey);
        }
        return shop;
    }
    Shop cachePenetration(Long id){  //缓存穿透
        String key = "cache:shop" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        // 判断是否是""
        if(shopJson!=null){
            return null;
        }
        //未命中
        Shop shop = getBaseMapper().selectById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",2L,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        return shop;
    }
    //使用redis中的setnx语句来实现加锁的操作
    //它可不是一个可重入锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    @Override
    @Transactional   //如果id为空，直接报错；如果redis中删除发生异常，则回滚数据库
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺信息不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete("cache:shop" + id);
        return Result.ok(shop);
    }
}
