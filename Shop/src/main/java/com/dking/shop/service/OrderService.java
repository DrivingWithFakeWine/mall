package com.dking.shop.service;

import com.dking.shop.dto.DirectOrderSubmitDTO;
import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.vo.OrderConfirmVO;
import com.dking.shop.vo.OrderDetailVO;
import com.dking.shop.vo.OrderListItemVO;
import com.dking.shop.vo.OrderPayVO;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;

import java.util.List;

public interface OrderService {

    /** 结算确认：当前用户已勾选且未失效的购物车商品 + 默认地址 + 合计 */
    OrderConfirmVO confirm(Long userId);

    /**
     * 提交订单：校验地址与库存 → 扣减库存 → 建订单/明细/地址快照 → 清购物车，
     * 全部在一个事务内完成。
     */
    OrderSubmitVO submit(Long userId, OrderSubmitDTO dto);

    /** 立即购买：不经过购物车，单 SKU 校验地址与库存后下单 */
    OrderSubmitVO submitDirect(Long userId, DirectOrderSubmitDTO dto);

    /** 我的订单列表；status 为空表示全部 */
    PageVO<OrderListItemVO> list(Long userId, int page, int size, Integer status);

    /** 订单详情：订单 + 明细快照 + 地址快照；不存在或非本人订单抛 404 */
    OrderDetailVO detail(Long userId, Long orderId);

    /** 模拟支付：仅待支付订单可支付；释放锁定库存并累加销量 */
    OrderPayVO pay(Long userId, Long orderId);

    /** 取消订单：仅待支付可取消；回滚库存（available + n、locked - n） */
    void cancel(Long userId, Long orderId);

    /** 超时任务用：取一批已过期未支付的订单 id，按 expire_time 升序 */
    List<Long> findExpiredWaitPayOrderIds(int limit);

    /**
     * 超时取消单笔订单：条件更新为已取消并回滚库存。
     * 返回 false 表示订单已被支付或已取消，属于正常并发结果。
     */
    boolean cancelExpiredOrder(Long orderId);
}
