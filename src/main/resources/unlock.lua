---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by lin.
--- DateTime: 2023/3/21 22:02
---

if(redis.call('get',KEYS[1])==ARGV[1]) then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0






