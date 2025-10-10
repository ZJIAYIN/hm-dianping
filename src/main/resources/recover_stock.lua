-- 参数：订单ID、优惠券ID、用户ID
local orderId = ARGV[1]
local voucherId = ARGV[2]
local userId = ARGV[3]

-- Redis键：库存键、用户下单记录键、已恢复订单记录（防重复）
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId -- 原一人一单的Set集合
local recoveredKey = "seckill:recovered:" .. voucherId -- 记录已恢复的订单

-- 1. 判断订单是否已恢复过（防重复消费）
if redis.call("sismember", recoveredKey, orderId) == 1 then
    return 0 -- 已恢复，直接返回
end

-- 2. 恢复库存（stock + 1）
redis.call("incrby", stockKey, 1)

-- 3. 从用户下单记录中移除（允许用户重新秒杀）
redis.call("srem", orderKey, userId)

-- 4. 记录已恢复的订单ID
redis.call("sadd", recoveredKey, orderId)

return 1 -- 恢复成功
