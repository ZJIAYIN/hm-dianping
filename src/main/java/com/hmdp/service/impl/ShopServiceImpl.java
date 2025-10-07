package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.hash.BloomFilter;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

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

    @Autowired
    ShopMapper shopMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private BloomFilter<Long> bloomFilter;

    @PostConstruct
    public void initBloomFilter() {
        int page = 1;
        int pageSize = 1000; // 每批处理 1000 条
        List<Long> batchIds;

        do {
            batchIds = shopMapper.selectObjs(
                            new QueryWrapper<Shop>()
                                    .select("id")
                                    .last("LIMIT " + (page - 1) * pageSize + "," + pageSize)
                    ).stream()
                    .map(obj -> ((BigInteger) obj).longValue())
                    .collect(Collectors.toList());


            for (Long id : batchIds) {
                bloomFilter.put(id);
            }

            page++;

        } while (!batchIds.isEmpty());
    }


    @Override
    public Result getById(Long id) {

        //缓存穿透
        Shop shop = queryWithPassThrough(id);
        //互斥锁 缓存击穿
        //Shop shop = queryWithMutex(id);
        //  逻辑过期 缓存击穿
        //Shop shop = queryWithLogicExpire(id);

        if(shop == null){
            return Result.fail("商铺不存在");
        }
        else{
            return Result.ok(shop);
        }


    }


    //缓存穿透
    public Shop queryWithPassThrough(Long id){

        if (!bloomFilter.mightContain(id)) {
            // 如果不在，说明数据库中肯定不存在，直接null
            return  null;
        }

        //从redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String jsonString = redisTemplate.opsForValue().get(key);

        //判断缓存是否命中
        if(StrUtil.isNotBlank(jsonString)){

            //不是空值
            //把json字符串转成对象
            Shop shop = JSONUtil.toBean(jsonString, Shop.class);
            //命中直接返回商铺信息
            return shop;
        };

        //判断缓存命中但是为空值
        if(jsonString != null){
            //是空值 结束
            return null;
        };


        //未命中缓存根据id查询数据库
        Shop shop = query().eq("id", id).one();

        //判断商品是否存在
        if(shop == null){
            //商铺不存在在缓存空值
            redisTemplate.opsForValue().set(key,"",2L, TimeUnit.MINUTES);
            return null;
        };


        //对象转json字符串 存redis
        String jsonStr = JSONUtil.toJsonStr(shop);
        redisTemplate.opsForValue().set(key,jsonStr,30L, TimeUnit.MINUTES);

        //存在返回商铺信息
        return shop;
    }


    //缓存击穿
    public Shop queryWithMutex(Long id){

        //从redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String jsonString = redisTemplate.opsForValue().get(key);

        //判断缓存是否命中
        if(StrUtil.isNotBlank(jsonString)){

            //不是空值
            //把json字符串转成对象
            Shop shop = JSONUtil.toBean(jsonString, Shop.class);
            //命中直接返回商铺信息
            return shop;
        };

        //判断缓存命中但是为空值
        if(jsonString != null){
            //是空值 结束
            return null;
        };

        //缓存未命中，尝试重构
        String lock_key = LOCK_SHOP_KEY + id;

        //尝试获取互斥锁
        try{
            if(!tryLock(lock_key)){
                //未获取锁，休眠一段时间
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            else{
                //获取锁
                //未命中缓存根据id查询数据库
                Shop shop = query().eq("id", id).one();
                Thread.sleep(200);

                //判断商品是否存在
                if(shop == null){
                    //商铺不存在在缓存空值
                    redisTemplate.opsForValue().set(key,"",2L, TimeUnit.MINUTES);
                    return null;
                };

                //商铺存在
                //对象转json字符串 存redis
                String jsonStr = JSONUtil.toJsonStr(shop);
                redisTemplate.opsForValue().set(key,jsonStr,30L, TimeUnit.MINUTES);

                //存在返回商铺信息
                return shop;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lock_key);
        }

    };

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //使用逻辑过期应对缓存击穿
    public Shop queryWithLogicExpire(Long id){

        //根据提交的商铺id从redis查询
        String key = CACHE_SHOP_KEY+id;
        String jsonStr = redisTemplate.opsForValue().get(key);

        //判断缓存是否命中

        //未命中直接返回
        if(jsonStr == null){
            return null;
        };

        //命中判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        //未过期直接返回商铺信息
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return shop;
        };

        //过期尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;

        //判断是否获取锁
        boolean tryLock = tryLock(lockKey);

        //未获取锁直接返回旧数据
        if(tryLock){
            //获取锁

            //再次检查是否过期
            if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
                return shop;
            };

            //开辟一个新线程
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                    try{
                        //重建缓存
                        this.saveShop2Redis(id,20L);
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }finally {
                        //释放互斥锁
                        unlock(lockKey);
                    }

                }
            );

        }

        //直接返回旧数据
        return shop;

    };


    //尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //尝试释放锁
    private void unlock(String key) {
        redisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop) {

        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺id不能为空");
        };

        // 写入数据库
        updateById(shop);

        //删除缓存中的数据
        String key = CACHE_SHOP_KEY + id;
        redisTemplate.delete(key);
        return Result.ok();

        //ZJY TODO 2025/8/14:这里感觉以后可以使用延迟异步双删优化一下

    }

    //缓存预热
    @Override
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {

        //查询店铺数据
        Shop shop =  query().eq("id",id).one();
        Thread.sleep(200);

        //封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //写入redis
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }
}
