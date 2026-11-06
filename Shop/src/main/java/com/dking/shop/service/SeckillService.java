package com.dking.shop.service;

import com.dking.shop.dto.SeckillOrderDTO;
import com.dking.shop.dto.SeckillOrderMessage;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.SeckillActivityDetailVO;
import com.dking.shop.vo.SeckillActivityListItemVO;
import com.dking.shop.vo.SeckillOrderSubmitVO;

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

    /**
     * 秒杀下单入口：异步开关打开时先返回 PENDING 由结果接口查询，否则等价于同步下单。
     */
    SeckillOrderSubmitVO submitOrder(Long userId, Long activityId, SeckillOrderDTO dto);

    /** 查询异步下单结果；Redis 过期后回落到 orders.order_token */
    SeckillOrderSubmitVO orderResult(Long userId, String token);

    /** 消费者处理队列消息：校验地址/活动后落库，失败回滚 Redis 预扣 */
    void processQueuedOrder(SeckillOrderMessage message);
}
