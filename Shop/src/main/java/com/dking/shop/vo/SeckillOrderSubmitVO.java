package com.dking.shop.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀下单结果：同步模式直接返回 SUCCESS，异步模式先返回 PENDING 再由结果接口查。
 */
@Data
public class SeckillOrderSubmitVO {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private String token;

    /** PENDING / SUCCESS / FAILED */
    private String status;

    private Long orderId;
    private String orderNo;
    private BigDecimal payAmount;
    private LocalDateTime expireTime;

    /** 失败原因，仅 FAILED 时有值 */
    private String message;

    public static SeckillOrderSubmitVO pending(String token) {
        SeckillOrderSubmitVO vo = new SeckillOrderSubmitVO();
        vo.setToken(token);
        vo.setStatus(PENDING);
        return vo;
    }

    public static SeckillOrderSubmitVO success(String token, OrderSubmitVO order) {
        SeckillOrderSubmitVO vo = new SeckillOrderSubmitVO();
        vo.setToken(token);
        vo.setStatus(SUCCESS);
        vo.setOrderId(order.getOrderId());
        vo.setOrderNo(order.getOrderNo());
        vo.setPayAmount(order.getPayAmount());
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    public static SeckillOrderSubmitVO failed(String token, String message) {
        SeckillOrderSubmitVO vo = new SeckillOrderSubmitVO();
        vo.setToken(token);
        vo.setStatus(FAILED);
        vo.setMessage(message);
        return vo;
    }
}
