package com.dking.shop.vo;

import lombok.Data;

import java.util.List;

/**
 * 通用分页外壳，对应契约中的 { total, records }，订单等其它列表可复用。
 * total 用 Integer 而非 Long：全局 Jackson 配置会把 Long 序列化成字符串，
 * 而契约要求 total 是数字（前端分页组件也要数字）。
 */
@Data
public class PageVO<T> {

    private Integer total;
    private List<T> records;
}
