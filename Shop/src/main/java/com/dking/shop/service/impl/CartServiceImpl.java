package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dking.shop.client.ProductClient;
import com.dking.shop.client.dto.SkuSnapshot;
import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.dto.CartSelectDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.service.CartService;
import com.dking.shop.vo.CartItemVO;
import com.dking.shop.vo.CartListVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车。
 *
 * <p>2026-11-23 起：商品信息（SKU 状态、价格、图片、可用库存）全部来自商品服务的
 * {@code /internal/product/skus/batch}，本服务不再直接读 {@code product_*} 表。</p>
 *
 * <p>注意：这里的库存校验只是「加购时提示」，**下单时以 reserve 的结果为准**。
 * 中间有几秒到几小时的窗口，库存随时可能被别人买走。</p>
 */
@Slf4j
@Service
public class CartServiceImpl implements CartService {

    private static final Integer SPU_STATUS_ON = 1;
    private static final Integer SKU_STATUS_ON = 1;
    private static final int MAX_QUANTITY = 99;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductClient productClient;

    private final Object[] cartLocks = new Object[64];

    public CartServiceImpl() {
        for (int i = 0; i < cartLocks.length; i++) {
            cartLocks[i] = new Object();
        }
    }

    private Object lockOf(Long userId) {
        return cartLocks[Math.floorMod(userId.hashCode(), cartLocks.length)];
    }

    @Override
    public void add(Long userId, CartAddDTO dto) {
        SkuSnapshot sku = loadSku(dto.getSkuId());
        // SKU 上架不代表 SPU 上架，两层都要校验（快照里都带着）
        if (!isSellable(sku)) {
            throw new BusinessException("商品已下架");
        }
        int available = sku.availableStock();

        int targetQuantity;
        synchronized (lockOf(userId)) {
            Cart existing = cartMapper.selectOne(
                    new LambdaQueryWrapper<Cart>()
                            .eq(Cart::getUserId, userId)
                            .eq(Cart::getSkuId, dto.getSkuId()));

            targetQuantity = dto.getQuantity() + (existing == null ? 0 : existing.getQuantity());
            if (targetQuantity > MAX_QUANTITY) {
                throw new BusinessException("单个商品数量不能超过 " + MAX_QUANTITY);
            }
            if (available < targetQuantity) {
                throw new BusinessException("库存不足");
            }

            if (existing != null) {
                // 只更新数量，避免整行写回覆盖并发修改的 selected
                cartMapper.update(null, new LambdaUpdateWrapper<Cart>()
                        .eq(Cart::getId, existing.getId())
                        .eq(Cart::getUserId, userId)
                        .set(Cart::getQuantity, targetQuantity));
            } else {
                Cart cart = new Cart();
                cart.setUserId(userId);
                cart.setSkuId(dto.getSkuId());
                cart.setQuantity(dto.getQuantity());
                cart.setSelected(1);
                try {
                    cartMapper.insert(cart);
                } catch (DuplicateKeyException e) {
                    // 并发加购同一 SKU：另一请求已插入，改为累加
                    throw new IllegalStateException("购物车并发插入冲突，userId=" + userId
                            + ", skuId=" + dto.getSkuId(), e);
                }
            }
        }

        log.info("加入购物车, userId={}, skuId={}, quantity={}, target={}",
                userId, dto.getSkuId(), dto.getQuantity(), targetQuantity);
    }

    @Override
    public CartListVO list(Long userId) {
        List<Cart> carts = cartMapper.selectList(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .orderByDesc(Cart::getCreateTime)
                        .orderByDesc(Cart::getId));

        CartListVO result = new CartListVO();
        if (carts.isEmpty()) {
            return result;
        }

        Map<Long, SkuSnapshot> skuMap = loadSkuMap(
                carts.stream().map(Cart::getSkuId).distinct().toList());

        List<CartItemVO> items = carts.stream()
                .map(cart -> toItemVO(cart, skuMap))
                .toList();
        result.setItems(items);

        log.info("查询购物车, userId={}, itemCount={}", userId, items.size());
        return result;
    }

    @Override
    public void updateQuantity(Long userId, CartQuantityDTO dto) {
        // 与 add 共用同一把用户锁，锁住"查数量 -> 算 -> 写数量"整段
        synchronized (lockOf(userId)) {
            Cart cart = cartMapper.selectOne(
                    new LambdaQueryWrapper<Cart>()
                            .eq(Cart::getId, dto.getId())
                            .eq(Cart::getUserId, userId));
            if (cart == null) {
                throw new NotFoundException("购物车项不存在");
            }

            SkuSnapshot sku = loadSku(cart.getSkuId());
            int available = sku == null ? 0 : sku.availableStock();
            if (available < dto.getQuantity()) {
                throw new BusinessException("库存不足");
            }

            // 只更新数量，避免整行写回覆盖并发修改的 selected
            int rows = cartMapper.update(null, new LambdaUpdateWrapper<Cart>()
                    .eq(Cart::getId, cart.getId())
                    .eq(Cart::getUserId, userId)
                    .set(Cart::getQuantity, dto.getQuantity()));
            if (rows == 0) {
                throw new NotFoundException("购物车项不存在");
            }
        }

        log.info("修改购物车数量, userId={}, cartId={}, quantity={}",
                userId, dto.getId(), dto.getQuantity());
    }

    @Override
    public void updateSelected(Long userId, CartSelectDTO dto) {
        // 只改 selected，归属校验放在 WHERE；影响 0 行说明记录不存在或不属于该用户
        int rows = cartMapper.update(null, new LambdaUpdateWrapper<Cart>()
                .eq(Cart::getId, dto.getId())
                .eq(Cart::getUserId, userId)
                .set(Cart::getSelected, dto.getSelected()));
        if (rows == 0) {
            throw new NotFoundException("购物车项不存在");
        }
        log.info("修改购物车勾选状态, userId={}, cartId={}, selected={}",
                userId, dto.getId(), dto.getSelected());
    }

    @Override
    public void remove(Long userId, Long id) {
        int rows = cartMapper.delete(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, id)
                .eq(Cart::getUserId, userId));
        if (rows == 0) {
            throw new NotFoundException("购物车项不存在");
        }
        log.info("删除购物车项, userId={}, cartId={}", userId, id);
    }

    // ------------------------------------------------------------------
    // 商品服务调用
    // ------------------------------------------------------------------

    private boolean isSellable(SkuSnapshot sku) {
        return sku != null
                && SKU_STATUS_ON.equals(sku.skuStatus())
                && SPU_STATUS_ON.equals(sku.spuStatus());
    }

    private SkuSnapshot loadSku(Long skuId) {
        List<SkuSnapshot> list = productClient.batchSkus(List.of(skuId)).getData();
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<Long, SkuSnapshot> loadSkuMap(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return productClient.batchSkus(skuIds).getData().stream()
                .collect(Collectors.toMap(SkuSnapshot::skuId, Function.identity()));
    }

    private CartItemVO toItemVO(Cart cart, Map<Long, SkuSnapshot> skuMap) {
        SkuSnapshot sku = skuMap.get(cart.getSkuId());
        int available = sku == null ? 0 : sku.availableStock();

        boolean invalid = !isSellable(sku) || available < cart.getQuantity();

        CartItemVO vo = new CartItemVO();
        vo.setId(cart.getId());
        vo.setSkuId(cart.getSkuId());
        vo.setSpuId(sku == null ? null : sku.spuId());
        vo.setName(sku == null ? "商品已失效" : sku.spuName());
        vo.setSpecDesc(sku == null ? "" : sku.specDesc());
        // 优先 SKU 图，没有则回退到 SPU 主图
        vo.setImage(sku != null && sku.image() != null ? sku.image() : (sku == null ? null : sku.spuMainImage()));
        vo.setPrice(sku == null ? BigDecimal.ZERO : sku.price());
        vo.setQuantity(cart.getQuantity());
        vo.setSelected(cart.getSelected());
        vo.setAvailableStock(available);
        vo.setInvalid(invalid);
        return vo;
    }
}
