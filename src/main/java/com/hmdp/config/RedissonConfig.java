package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置redisson客户端
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.214.139:6379").setPassword("125874");
        // 创建redisson对象
        return Redisson.create(config);

    }

}
