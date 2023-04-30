package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService proxy;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    // 创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 初始化lua脚本文本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT =new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    // 创建任务

    // 初始化类后直接运行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                // 获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常！",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        // 处理订单
        Long userId = voucherOrder.getUserId();
        // 使用redisson锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();
        if(!isLock){
            // 获取锁失败
            log.error("不允许重复下单");
            return;
        }

       try {
           // 获取代理对象（提交事务）
           proxy.createVoucherOrder(voucherOrder);
       } finally {
           lock.unlock();
       }
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        // 查询id对应的优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 判断秒杀活动时间段和库存信息
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始!");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束!");
//        }
//
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足！");
//        }
//
//        // 一人一单判断
//        Long userId = UserHolder.getUser().getId();
//
//        // 创建锁对象(使用自定义的分布式锁)
////        SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//
//        // 使用redisson锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            // 获取锁失败
//            return Result.fail("不允许重复下单！");
//        }
//
//       try {
//           // 获取代理对象（提交事务）
//           IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//           return proxy.createVoucherOrder(voucherId);
//       } finally {
//           lock.unlock();
//       }
//
//    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 执行Lua脚本判断用户是否有购买资格(返回0表示有资格)
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());

        if(result.intValue()!=0){
            return Result.fail(result.intValue()==1?"库存不足":"重复下单");
        }

        // TODO 若有资格 保存到阻塞队列 让其他线程执行写操作

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        //  添加至阻塞队列中去
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);

    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        // 一人一单判断
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("不能重复下单");
            return;
        }

        // 扣减库存(乐观锁保证线程安全问题，用库存代替版本号)
        boolean success = seckillVoucherService.update().
                setSql("stock = stock-1").
                eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).
                update();

        if (!success) {
            log.error("库存不足！");
            return;
        }

        // 创建订单

        save(voucherOrder);

    }
}
