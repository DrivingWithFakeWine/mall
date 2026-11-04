package com.dking.shop.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SeckillActivityDetailVO extends SeckillActivityListItemVO {

    private Integer perUserLimit;

    /** 服务器当前时间：前端倒计时以它为准，避免客户端时钟偏差 */
    private LocalDateTime serverTime;
}
