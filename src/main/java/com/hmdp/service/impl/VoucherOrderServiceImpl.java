package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    //查询秒杀券的相关信息,所以用ISeckillVoucherService
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    IVoucherOrderService proxy;
    static {
        SECKILL_SCRIPT  = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("JudgeSecKillStock.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
        public void handleVoucherOrder(VoucherOrder voucherOrder){
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            if(proxy==null) log.error("proxy:null--2");
            proxy.createVoucherOrder(voucherOrder);
        }

    }
    @Override
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),userId.toString(),String.valueOf(orderId));
        int r = result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        orderTasks.add(voucherOrder);

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        if(proxy==null) log.error("proxy:null--1");
        //返回订单id
        return Result.ok(orderId);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId){
//        //查询秒杀券的相关信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if(LocalDateTime.now().isBefore(beginTime)){
//            return Result.fail("还未开始");
//        }
//        if(LocalDateTime.now().isAfter(endTime)){
//            return Result.fail("已经结束了");
//        }
//        Integer stock = seckillVoucher.getStock();
//        if(stock<1){
//            return Result.fail("优惠券已抢光");
//        }
//        Long userId = UserHolder.getUser().getId();
//
////        SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:"+userId);
//        //尝试获取锁，参数分别是：获取锁的最大等待时间(期间会重试)，锁自动释放时间，时间单位
////        boolean isLock = lock.tryLock(1,10, TimeUnit.SECONDS);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("不可重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5. 一人一单
        Long voucherId = voucherOrder.getVoucherId();
        //下面的查询订单，判断用户是否买过，没必要，因为redis判断过了
//        //5.1 查询订单
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        // 5.2.判断是否存在
//        if (count > 0) {
//            // 用户已经购买过了
//            log.error("用户已经购买过一次！");
//            return ;
//        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if (!success) {
            //扣减库存
            log.error("库存不足");
            return ;
        }
        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

    }
}
