package com.dking.shop.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.ProductStockMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * updateQuantity 是绝对赋值，不存在"丢更新"，这里测的是归属校验、库存校验和落库结果。
 * 测试事务会在每个方法结束后回滚，夹具不会留在测试库。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Transactional
class CartUpdateQuantityTest {

    private static final int AVAILABLE_STOCK = 10;
    private static final int INITIAL_QUANTITY = 1;

    @Resource
    private CartService cartService;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    private Long userId;
    private Long otherUserId;
    private Long cartId;

    @BeforeEach
    void setUp() {
        long base = IdWorker.getId();
        userId = base;
        otherUserId = base + 1;
        Long skuId = base + 2;

        ProductStock stock = new ProductStock();
        stock.setSkuId(skuId);
        stock.setAvailableStock(AVAILABLE_STOCK);
        productStockMapper.insert(stock);

        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setSkuId(skuId);
        cart.setQuantity(INITIAL_QUANTITY);
        cart.setSelected(1);
        cartMapper.insert(cart);
        cartId = cart.getId();
    }

    @Test
    void updateQuantity_success() {
        cartService.updateQuantity(userId, dto(cartId, 5));

        assertEquals(5, cartMapper.selectById(cartId).getQuantity());
    }

    @Test
    void updateQuantity_toStockBoundary_shouldSucceed() {
        cartService.updateQuantity(userId, dto(cartId, AVAILABLE_STOCK));

        assertEquals(AVAILABLE_STOCK, cartMapper.selectById(cartId).getQuantity());
    }

    @Test
    void updateQuantity_exceedsStock_shouldThrowAndKeepOriginal() {
        assertThrows(BusinessException.class,
                () -> cartService.updateQuantity(userId, dto(cartId, AVAILABLE_STOCK + 1)));

        assertEquals(INITIAL_QUANTITY, cartMapper.selectById(cartId).getQuantity());
    }

    @Test
    void updateQuantity_notOwner_shouldThrowAndKeepOriginal() {
        assertThrows(NotFoundException.class,
                () -> cartService.updateQuantity(otherUserId, dto(cartId, 5)));

        assertEquals(INITIAL_QUANTITY, cartMapper.selectById(cartId).getQuantity());
    }

    @Test
    void updateQuantity_cartNotFound_shouldThrow() {
        assertThrows(NotFoundException.class,
                () -> cartService.updateQuantity(userId, dto(IdWorker.getId(), 5)));
    }

    private CartQuantityDTO dto(Long id, int quantity) {
        CartQuantityDTO dto = new CartQuantityDTO();
        dto.setId(id);
        dto.setQuantity(quantity);
        return dto;
    }
}
