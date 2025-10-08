package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.limiting.LimitType;
import com.hmdp.limiting.RateLimiter;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Autowired
    IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @RateLimiter(key = "#voucherId", count = 3, time = 10, limitType = LimitType.IP)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
