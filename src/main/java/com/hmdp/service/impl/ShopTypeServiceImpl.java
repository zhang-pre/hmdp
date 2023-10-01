package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
        String key = "cache:shop:list" ;
        String shopListJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopListJson)){
            return Result.ok(JSONUtil.toList(shopListJson,ShopType.class));
        }
        //未命中
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if(shopTypes.isEmpty()){
            return Result.fail("商户不存在");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypes));

        return Result.ok(shopTypes);

    }
}
