package com.dking.shop.service;

import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.vo.OrderConfirmVO;
import com.dking.shop.vo.OrderDetailVO;
import com.dking.shop.vo.OrderListItemVO;
import com.dking.shop.vo.OrderPayVO;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;

public interface OrderService {

    /** 结算确认：当前用户已勾选且未失效的购物车商品 + 默认地址 + 合计 */
    OrderConfirmVO confirm(Long userId);

    /**
     * 提交订单：校验地址与库存 → 扣减库存 → 建订单/明细/地址快照 → 清购物车，
     * 全部在一个事务内完成。
     */
    OrderSubmitVO submit(Long userId, OrderSubmitDTO dto);

    /** 我的订单列表；status 为空表示全部 */
    PageVO<OrderListItemVO> list(Long userId, int page, int size, Integer status);

    /** 订单详情：订单 + 明细快照 + 地址快照；不存在或非本人订单抛 404 */
    OrderDetailVO detail(Long userId, Long orderId);

    /** 模拟支付：仅待支付订单可支付；释放锁定库存并累加销量 */
    OrderPayVO pay(Long userId, Long orderId);

    /** 取消订单：仅待支付可取消；回滚库存（available + n、locked - n） */
    void cancel(Long userId, Long orderId);
}
