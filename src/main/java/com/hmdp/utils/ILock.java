package com.hmdp.utils;

// 利用redis实现分布式锁接口
public interface ILock {
    // 获取锁释放锁
    boolean tryLock(long timeoutSec);
    void unlock();
}
