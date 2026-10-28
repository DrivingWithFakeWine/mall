package com.dking.shop.service;

import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.dto.CartSelectDTO;
import com.dking.shop.vo.CartListVO;

public interface CartService {

    /** 加入购物车：同一 SKU 累加数量，校验商品状态与库存 */
    void add(Long userId, CartAddDTO dto);

    /** 购物车列表：含商品信息、库存与失效标记 */
    CartListVO list(Long userId);

    /** 修改购物车项数量（设置绝对值），校验归属与库存 */
    void updateQuantity(Long userId, CartQuantityDTO dto);

    /** 勾选 / 取消勾选购物车项 */
    void updateSelected(Long userId, CartSelectDTO dto);

    /** 删除购物车项（物理删除） */
    void remove(Long userId, Long id);
}
