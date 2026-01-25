package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result setkillVoucher(Long voucherId);

    //Result createVoucherOrder(Long voucherId);

    /* @Override
        public Result setkillVoucher(Long voucherId) {
            //1. 查询优惠卷
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            //2. 判断秒杀是否开始
            if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
                return Result.fail("秒杀尚未开始！");
            }
            //3. 判断秒杀是否结束
            if(voucher.getEndTime().isBefore(LocalDateTime.now())){
                return Result.fail("秒杀已结束！");
            }
            //4. 判断是否有库存
            if(voucher.getStock() < 1){
                return Result.fail("库存不足！");
            }

            //创建订单
            return createVoucherOrder(voucherId);
        }*/

    @Transactional
    //保证：扣库存 + 创建订单 原子性
    Result createVoucherOrder(VoucherOrder voucherOrder);
}
