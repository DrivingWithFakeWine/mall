package com.dking.shop.service;

import com.dking.shop.dto.SeckillOrderDTO;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.SeckillActivityDetailVO;
import com.dking.shop.vo.SeckillActivityListItemVO;

public interface SeckillService {

    /** 秒杀活动列表：上线且未结束，按开始时间升序；keyword 匹配活动名或商品名 */
    PageVO<SeckillActivityListItemVO> list(int page, int size, String keyword);

    /** 活动详情；不存在或已下线抛 404 */
    SeckillActivityDetailVO detail(Long activityId);

    /**
     * 秒杀下单：条件扣活动库存 → 建订单/明细/地址快照，单事务完成。
     * 每个用户每个活动只能成功一次（由 uk_activity_user 兜底）。
     */
    OrderSubmitVO submit(Long userId, Long activityId, SeckillOrderDTO dto);
}
