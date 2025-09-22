-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId


--- 判断库存是否充足

if (tonumber(redis.call("get",stockKey)) <= 0 ) then
    ---库存不足
    return 1
end

--- 判断用户是否下单

if (tonumber(redis.call("sismember",orderKey,userId)) == 1) then
    ---已下单
    return 2
end

--扣减库存
redis.call("incrby",stockKey,-1)

--将userId存入当前的Set集合
redis.call("sadd",orderKey,userId)

return 0