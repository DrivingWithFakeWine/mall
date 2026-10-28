package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dking.shop.dto.CartAddDTO;
import com.dking.shop.dto.CartQuantityDTO;
import com.dking.shop.dto.CartSelectDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
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

@Slf4j
@Service
public class CartServiceImpl implements CartService {

    private static final Integer SPU_STATUS_ON = 1;
    private static final Integer SKU_STATUS_ON = 1;
    private static final int MAX_QUANTITY = 99;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Override
    public void add(Long userId, CartAddDTO dto) {
        ProductSku sku = productSkuMapper.selectById(dto.getSkuId());
        if (sku == null || !SKU_STATUS_ON.equals(sku.getStatus())) {
            throw new BusinessException("商品已下架");
        }

        // SKU 上架不代表 SPU 上架，两层都要校验
        ProductSpu spu = productSpuMapper.selectById(sku.getSpuId());
        if (spu == null || !SPU_STATUS_ON.equals(spu.getStatus())) {
            throw new BusinessException("商品已下架");
        }

        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>()
                        .eq(ProductStock::getSkuId, dto.getSkuId()));
        int available = stock == null ? 0 : stock.getAvailableStock();

        Cart existing = cartMapper.selectOne(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .eq(Cart::getSkuId, dto.getSkuId()));

        int targetQuantity = dto.getQuantity() + (existing == null ? 0 : existing.getQuantity());
        if (targetQuantity > MAX_QUANTITY) {
            throw new BusinessException("单个商品数量不能超过 " + MAX_QUANTITY);
        }
        if (available < targetQuantity) {
            throw new BusinessException("库存不足");
        }

        if (existing != null) {
            existing.setQuantity(targetQuantity);
            cartMapper.updateById(existing);
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
                Cart current = cartMapper.selectOne(
                        new LambdaQueryWrapper<Cart>()
                                .eq(Cart::getUserId, userId)
                                .eq(Cart::getSkuId, dto.getSkuId()));
                if (current != null) {
                    current.setQuantity(Math.min(current.getQuantity() + dto.getQuantity(), MAX_QUANTITY));
                    cartMapper.updateById(current);
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

        List<Long> skuIds = carts.stream().map(Cart::getSkuId).distinct().toList();
        Map<Long, ProductSku> skuMap = productSkuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        List<Long> spuIds = skuMap.values().stream()
                .map(ProductSku::getSpuId)
                .distinct()
                .toList();
        Map<Long, ProductSpu> spuMap = spuIds.isEmpty()
                ? Map.of()
                : productSpuMapper.selectBatchIds(spuIds).stream()
                        .collect(Collectors.toMap(ProductSpu::getId, Function.identity()));

        Map<Long, ProductStock> stockMap = productStockMapper.selectList(
                        new LambdaQueryWrapper<ProductStock>().in(ProductStock::getSkuId, skuIds))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));

        List<CartItemVO> items = carts.stream()
                .map(cart -> toItemVO(cart, skuMap, spuMap, stockMap))
                .toList();
        result.setItems(items);

        log.info("查询购物车, userId={}, itemCount={}", userId, items.size());
        return result;
    }

    @Override
    public void updateQuantity(Long userId, CartQuantityDTO dto) {
        Cart cart = cartMapper.selectOne(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getId, dto.getId())
                        .eq(Cart::getUserId, userId));
        if (cart == null) {
            throw new NotFoundException("购物车项不存在");
        }

        ProductStock stock = productStockMapper.selectOne(
                new LambdaQueryWrapper<ProductStock>()
                        .eq(ProductStock::getSkuId, cart.getSkuId()));
        int available = stock == null ? 0 : stock.getAvailableStock();
        if (available < dto.getQuantity()) {
            throw new BusinessException("库存不足");
        }

        cart.setQuantity(dto.getQuantity());
        cartMapper.updateById(cart);

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

    private CartItemVO toItemVO(Cart cart,
                                Map<Long, ProductSku> skuMap,
                                Map<Long, ProductSpu> spuMap,
                                Map<Long, ProductStock> stockMap) {
        ProductSku sku = skuMap.get(cart.getSkuId());
        ProductSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
        ProductStock stock = stockMap.get(cart.getSkuId());
        int available = stock == null ? 0 : stock.getAvailableStock();

        boolean invalid = sku == null
                || !SKU_STATUS_ON.equals(sku.getStatus())
                || spu == null
                || !SPU_STATUS_ON.equals(spu.getStatus())
                || available < cart.getQuantity();

        CartItemVO vo = new CartItemVO();
        vo.setId(cart.getId());
        vo.setSkuId(cart.getSkuId());
        vo.setSpuId(sku == null ? null : sku.getSpuId());
        vo.setName(spu == null ? "商品已失效" : spu.getName());
        vo.setSpecDesc(sku == null ? "" : sku.getSpecDesc());
        // 优先 SKU 图，没有则回退到 SPU 主图
        vo.setImage(sku != null && sku.getImage() != null
                ? sku.getImage()
                : (spu == null ? null : spu.getMainImage()));
        vo.setPrice(sku == null ? BigDecimal.ZERO : sku.getPrice());
        vo.setQuantity(cart.getQuantity());
        vo.setSelected(cart.getSelected());
        vo.setAvailableStock(available);
        vo.setInvalid(invalid);
        return vo;
    }
}
