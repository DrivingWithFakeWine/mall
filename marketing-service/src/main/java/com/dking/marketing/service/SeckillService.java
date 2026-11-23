package com.dking.marketing.service;

import com.dking.marketing.dto.SeckillOrderDTO;
import com.dking.marketing.vo.PageVO;
import com.dking.marketing.vo.SeckillActivityDetailVO;
import com.dking.marketing.vo.SeckillActivityListItemVO;
import com.dking.marketing.vo.SeckillOrderSubmitVO;

/**
 * 秒杀（营销域）。
 *
 * <p>只负责活动本身的读、活动库存的扣减、以及发布建单消息；
 * 订单的创建、支付、取消都属于交易域，营销不碰 {@code orders} 表。</p>
 */
public interface SeckillService {

    /** 秒杀活动列表：上线且未结束，按开始时间升序；keyword 匹配活动名或商品名 */
    PageVO<SeckillActivityListItemVO> list(int page, int size, String keyword);

    /** 活动详情；不存在或已下线抛 404 */
    SeckillActivityDetailVO detail(Long activityId);

    /**
     * 秒杀下单：Redis 闸门预扣 → 扣活动桶 → 发布建单消息，返回 PENDING + token。
     *
     * <p>扣桶和发消息之间如果失败，会把桶和 Redis 预扣都回滚，保证「有货才有单」。</p>
     */
    SeckillOrderSubmitVO submitOrder(Long userId, Long activityId, SeckillOrderDTO dto);

    /** 查询下单结果：只读 Redis 结果缓存，由交易服务消费后写入 */
    SeckillOrderSubmitVO orderResult(Long userId, String token);
}
