package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.shop.dto.DirectOrderSubmitDTO;
import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderAddress;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.OrderAddressMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.service.OrderService;
import com.dking.shop.service.SeckillStockService;
import com.dking.shop.vo.ConfirmItemVO;
import com.dking.shop.vo.OrderAddressVO;
import com.dking.shop.vo.OrderConfirmVO;
import com.dking.shop.vo.OrderDetailVO;
import com.dking.shop.vo.OrderItemVO;
import com.dking.shop.vo.OrderListItemVO;
import com.dking.shop.vo.OrderPayVO;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.UserAddressVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    private static final Integer SPU_STATUS_ON = 1;
    private static final Integer SKU_STATUS_ON = 1;
    private static final Integer CART_SELECTED = 1;
    private static final Integer ADDRESS_DEFAULT = 1;
    private static final Integer ORDER_STATUS_WAIT_PAY = 0;
    private static final Integer ORDER_STATUS_PAID = 1;
    private static final Integer ORDER_STATUS_CANCELLED = 4;
    private static final Long SINGLE_MERCHANT_ID = 0L;
    private static final int PAY_TIMEOUT_MINUTES = 30;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_TIMEOUT_BATCH_SIZE = 1000;

    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private OrderAddressMapper orderAddressMapper;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private TransactionTemplate transactionTemplate;


    private final Object[] cartLocks = new Object[64];
    public OrderServiceImpl() {
        for (int i = 0; i < cartLocks.length; i++) {
            cartLocks[i] = new Object();
        }
    }

    private Object lockOf(Long userId) {
        return cartLocks[Math.floorMod(userId.hashCode(), cartLocks.length)];
    }

    @Override
    public OrderConfirmVO confirm(Long userId) {
        List<Cart> carts = cartMapper.selectList(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .eq(Cart::getSelected, CART_SELECTED)
                        .orderByDesc(Cart::getCreateTime)
                        .orderByDesc(Cart::getId));
        if (carts.isEmpty()) {
            throw new BusinessException("请先选择要结算的商品");
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

        List<ConfirmItemVO> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Cart cart : carts) {
            ProductSku sku = skuMap.get(cart.getSkuId());
            ProductSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
            ProductStock stock = stockMap.get(cart.getSkuId());
            int available = stock == null ? 0 : stock.getAvailableStock();

            // 失效商品不进入结算（购物车页已提示过）
            boolean invalid = sku == null
                    || !SKU_STATUS_ON.equals(sku.getStatus())
                    || spu == null
                    || !SPU_STATUS_ON.equals(spu.getStatus())
                    || available < cart.getQuantity();
            if (invalid) {
                continue;
            }

            BigDecimal subtotal = sku.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));

            ConfirmItemVO item = new ConfirmItemVO();
            item.setCartId(cart.getId());
            item.setSkuId(cart.getSkuId());
            item.setSpuId(sku.getSpuId());
            item.setName(spu.getName());
            item.setSpecDesc(sku.getSpecDesc());
            item.setImage(sku.getImage() != null ? sku.getImage() : spu.getMainImage());
            item.setPrice(sku.getPrice());
            item.setQuantity(cart.getQuantity());
            item.setSubtotal(subtotal);

            items.add(item);
            totalAmount = totalAmount.add(subtotal);
        }

        if (items.isEmpty()) {
            throw new BusinessException("选中的商品已失效，请返回购物车调整");
        }

        OrderConfirmVO vo = new OrderConfirmVO();
        vo.setItems(items);
        vo.setTotalAmount(totalAmount);
        vo.setAddress(loadDefaultAddress(userId));

        log.info("结算确认, userId={}, itemCount={}, totalAmount={}", userId, items.size(), totalAmount);
        return vo;
    }

    @Override
    public OrderSubmitVO submit(Long userId, OrderSubmitDTO dto) {
        synchronized (lockOf(userId)) {
            return transactionTemplate.execute(status -> dosubmit(userId, dto));
        }
    }

    private OrderSubmitVO dosubmit(Long userId, OrderSubmitDTO dto) {
        // ① 地址必须属于当前用户（防越权）
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, dto.getAddressId())
                        .eq(UserAddress::getUserId, userId));
        if (address == null) {
            throw new BusinessException("收货地址不存在");
        }

// ② 选中购物车项
        List<Cart> carts = cartMapper.selectList(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .eq(Cart::getSelected, CART_SELECTED)
                        .orderByDesc(Cart::getCreateTime)
                        .orderByDesc(Cart::getId));
        if (carts.isEmpty()) {
            throw new BusinessException("请先选择要结算的商品");
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


        // ③ 先只读校验全部商品，避免无效请求触发扣减与回滚
        for (Cart cart : carts) {
            ProductSku sku = skuMap.get(cart.getSkuId());
            ProductSpu spu = sku == null ? null : spuMap.get(sku.getSpuId());
            ProductStock stock = stockMap.get(cart.getSkuId());
            int available = stock == null ? 0 : stock.getAvailableStock();

            if (sku == null
                    || !SKU_STATUS_ON.equals(sku.getStatus())
                    || spu == null
                    || !SPU_STATUS_ON.equals(spu.getStatus())) {
                throw new BusinessException("商品已下架，请返回购物车调整");
            }
            if (available < cart.getQuantity()) {
                throw new BusinessException("库存不足：" + spu.getName());
            }
        }

        // ④ 原子扣库存：available-n、locked+n；影响 0 行说明已被并发买走
        for (Cart cart : carts) {
            int rows = productStockMapper.update(null,
                    new LambdaUpdateWrapper<ProductStock>()
                            .eq(ProductStock::getSkuId, cart.getSkuId())
                            .ge(ProductStock::getAvailableStock, cart.getQuantity())
                            .setSql("available_stock = available_stock - " + cart.getQuantity())
                            .setSql("locked_stock = locked_stock + " + cart.getQuantity()));
            if (rows == 0) {
                ProductSpu spu = spuMap.get(skuMap.get(cart.getSkuId()).getSpuId());
                throw new BusinessException("库存不足：" + spu.getName());
            }
        }

        // ⑤ 生成订单
        LocalDateTime now = LocalDateTime.now();
        String orderNo = generateOrderNo(now);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Cart cart : carts) {
            ProductSku sku = skuMap.get(cart.getSkuId());
            totalAmount = totalAmount.add(
                    sku.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setMerchantId(SINGLE_MERCHANT_ID);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(ORDER_STATUS_WAIT_PAY);
        order.setExpireTime(now.plusMinutes(PAY_TIMEOUT_MINUTES));
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);

        // ⑥ 订单明细（快照）
        for (Cart cart : carts) {
            ProductSku sku = skuMap.get(cart.getSkuId());
            ProductSpu spu = spuMap.get(sku.getSpuId());

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setOrderNo(orderNo);
            item.setSpuId(sku.getSpuId());
            item.setSkuId(sku.getId());
            item.setProductName(spu.getName());
            item.setSpecDesc(sku.getSpecDesc());
            item.setImage(sku.getImage() != null ? sku.getImage() : spu.getMainImage());
            item.setPrice(sku.getPrice());
            item.setQuantity(cart.getQuantity());
            item.setTotalAmount(sku.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
            orderItemMapper.insert(item);
        }

        // ⑦ 订单收货地址快照
        OrderAddress orderAddress = new OrderAddress();
        orderAddress.setOrderId(order.getId());
        orderAddress.setReceiverName(address.getReceiverName());
        orderAddress.setReceiverPhone(address.getReceiverPhone());
        orderAddress.setProvince(address.getProvince());
        orderAddress.setCity(address.getCity());
        orderAddress.setDistrict(address.getDistrict());
        orderAddress.setDetail(address.getDetail());
        orderAddressMapper.insert(orderAddress);

        // ⑧ 只清理本次下单的购物车项
        List<Long> cartIds = carts.stream().map(Cart::getId).toList();
        cartMapper.deleteBatchIds(cartIds);


        log.info("提交订单成功, userId={}, orderNo={}, payAmount={}, itemCount={}",
                userId, orderNo, totalAmount, carts.size());

        OrderSubmitVO vo = new OrderSubmitVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(orderNo);
        vo.setPayAmount(totalAmount);
        vo.setStatus(ORDER_STATUS_WAIT_PAY);
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmitVO submitDirect(Long userId, DirectOrderSubmitDTO dto) {
        // ① 地址必须属于当前用户（防越权）
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, dto.getAddressId())
                        .eq(UserAddress::getUserId, userId));
        if (address == null) {
            throw new BusinessException("收货地址不存在");
        }

        // ② 商品可售校验
        ProductSku sku = productSkuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException("商品不存在");
        }
        ProductSpu spu = productSpuMapper.selectById(sku.getSpuId());
        if (spu == null) {
            throw new BusinessException("商品不存在");
        }
        if (!SKU_STATUS_ON.equals(sku.getStatus()) || !SPU_STATUS_ON.equals(spu.getStatus())) {
            throw new BusinessException("商品已下架");
        }

        // ③ 原子扣库存：available-n、locked+n；影响 0 行说明库存不足
        int quantity = dto.getQuantity();
        int rows = productStockMapper.update(null,
                new LambdaUpdateWrapper<ProductStock>()
                        .eq(ProductStock::getSkuId, sku.getId())
                        .ge(ProductStock::getAvailableStock, quantity)
                        .setSql("available_stock = available_stock - " + quantity)
                        .setSql("locked_stock = locked_stock + " + quantity));
        if (rows == 0) {
            throw new BusinessException("库存不足：" + spu.getName());
        }

        // ④ 生成订单（不经购物车，不删除任何购物车项）
        LocalDateTime now = LocalDateTime.now();
        String orderNo = generateOrderNo(now);
        BigDecimal amount = sku.getPrice().multiply(BigDecimal.valueOf(quantity));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setMerchantId(SINGLE_MERCHANT_ID);
        order.setTotalAmount(amount);
        order.setPayAmount(amount);
        order.setStatus(ORDER_STATUS_WAIT_PAY);
        order.setExpireTime(now.plusMinutes(PAY_TIMEOUT_MINUTES));
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setOrderNo(orderNo);
        item.setSpuId(sku.getSpuId());
        item.setSkuId(sku.getId());
        item.setProductName(spu.getName());
        item.setSpecDesc(sku.getSpecDesc());
        item.setImage(sku.getImage() != null ? sku.getImage() : spu.getMainImage());
        item.setPrice(sku.getPrice());
        item.setQuantity(quantity);
        item.setTotalAmount(amount);
        orderItemMapper.insert(item);

        OrderAddress orderAddress = new OrderAddress();
        orderAddress.setOrderId(order.getId());
        orderAddress.setReceiverName(address.getReceiverName());
        orderAddress.setReceiverPhone(address.getReceiverPhone());
        orderAddress.setProvince(address.getProvince());
        orderAddress.setCity(address.getCity());
        orderAddress.setDistrict(address.getDistrict());
        orderAddress.setDetail(address.getDetail());
        orderAddressMapper.insert(orderAddress);

        log.info("立即购买下单成功, userId={}, orderNo={}, skuId={}, quantity={}, payAmount={}",
                userId, orderNo, sku.getId(), quantity, amount);

        OrderSubmitVO vo = new OrderSubmitVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(orderNo);
        vo.setPayAmount(amount);
        vo.setStatus(ORDER_STATUS_WAIT_PAY);
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    @Override
    public PageVO<OrderListItemVO> list(Long userId, int page, int size, Integer status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime)
                .orderByDesc(Order::getId);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }

        Page<Order> result = orderMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        PageVO<OrderListItemVO> pageVO = new PageVO<>();
        pageVO.setTotal(Math.toIntExact(result.getTotal()));

        if (result.getRecords().isEmpty()) {
            pageVO.setRecords(List.of());
            return pageVO;
        }

        List<Long> orderIds = result.getRecords().stream().map(Order::getId).toList();
        Map<Long, List<OrderItem>> itemsByOrder = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>()
                                .in(OrderItem::getOrderId, orderIds)
                                .orderByAsc(OrderItem::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        List<OrderListItemVO> records = result.getRecords().stream()
                .map(order -> {
                    List<OrderItem> items = itemsByOrder.getOrDefault(order.getId(), List.of());
                    OrderItem firstItem = items.isEmpty() ? null : items.get(0);

                    OrderListItemVO vo = new OrderListItemVO();
                    vo.setId(order.getId());
                    vo.setOrderNo(order.getOrderNo());
                    vo.setStatus(order.getStatus());
                    vo.setPayAmount(order.getPayAmount());
                    vo.setCreateTime(order.getCreateTime());
                    vo.setFirstItemName(firstItem == null ? null : firstItem.getProductName());
                    vo.setFirstItemImage(firstItem == null ? null : firstItem.getImage());
                    vo.setItemCount(items.stream().mapToInt(OrderItem::getQuantity).sum());
                    return vo;
                })
                .toList();
        pageVO.setRecords(records);

        log.info("查询订单列表, userId={}, status={}, page={}, size={}, total={}",
                userId, status, safePage, safeSize, result.getTotal());
        return pageVO;
    }

    @Override
    public OrderDetailVO detail(Long userId, Long orderId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId));
        if (order == null) {
            throw new NotFoundException("订单不存在");
        }

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId)
                        .orderByAsc(OrderItem::getId));

        OrderAddress orderAddress = orderAddressMapper.selectOne(
                new LambdaQueryWrapper<OrderAddress>()
                        .eq(OrderAddress::getOrderId, orderId));

        OrderDetailVO vo = new OrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setRemark(order.getRemark());
        vo.setCreateTime(order.getCreateTime());
        vo.setExpireTime(order.getExpireTime());
        vo.setPayTime(order.getPayTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setItems(items.stream().map(this::toItemVO).toList());
        if (orderAddress != null) {
            vo.setAddress(toAddressVO(orderAddress));
        }

        log.info("查询订单详情, userId={}, orderId={}, itemCount={}", userId, orderId, items.size());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderPayVO pay(Long userId, Long orderId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId));
        if (order == null) {
            throw new NotFoundException("订单不存在");
        }
        if (!ORDER_STATUS_WAIT_PAY.equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许支付");
        }

        LocalDateTime now = LocalDateTime.now();
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(now)) {
            throw new BusinessException("订单已超时，请重新下单");
        }

        // ① 状态迁移：条件更新，影响 0 行说明已被并发修改
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                .gt(Order::getExpireTime, now)
                .set(Order::getStatus, ORDER_STATUS_PAID)
                .set(Order::getPayTime, now));
        if (rows == 0) {
            throw new BusinessException("订单状态不允许支付");
        }

        // ② 释放锁定库存（普通订单）+ 累加销量；秒杀订单的库存在活动池，这里只加销量
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        boolean fromSeckill = order.getActivityId() != null;

        for (OrderItem item : items) {
            if (!fromSeckill) {
                int updated = productStockMapper.update(null,
                        new LambdaUpdateWrapper<ProductStock>()
                                .eq(ProductStock::getSkuId, item.getSkuId())
                                .ge(ProductStock::getLockedStock, item.getQuantity())
                                .setSql("locked_stock = locked_stock - " + item.getQuantity()));
                if (updated == 0) {
                    throw new BusinessException("库存数据异常，请联系客服");
                }
            }

            productSkuMapper.update(null, new LambdaUpdateWrapper<ProductSku>()
                    .eq(ProductSku::getId, item.getSkuId())
                    .setSql("sales = sales + " + item.getQuantity()));
            productSpuMapper.update(null, new LambdaUpdateWrapper<ProductSpu>()
                    .eq(ProductSpu::getId, item.getSpuId())
                    .setSql("sales = sales + " + item.getQuantity()));
        }

        log.info("订单支付成功, userId={}, orderNo={}, payAmount={}",
                userId, order.getOrderNo(), order.getPayAmount());

        OrderPayVO vo = new OrderPayVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setPayAmount(order.getPayAmount());
        vo.setStatus(ORDER_STATUS_PAID);
        vo.setPayTime(now);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId));
        if (order == null) {
            throw new NotFoundException("订单不存在");
        }
        if (!ORDER_STATUS_WAIT_PAY.equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许取消");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                .set(Order::getStatus, ORDER_STATUS_CANCELLED)
                .set(Order::getCancelTime, now));
        if (rows == 0) {
            throw new BusinessException("订单状态不允许取消");
        }

        // 按订单来源回滚库存：普通订单归还 product_stock，秒杀订单归还活动库存
        releaseOrderStock(order);

        log.info("订单取消成功, userId={}, orderNo={}", userId, order.getOrderNo());
    }

    @Override
    public List<Long> findExpiredWaitPayOrderIds(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_TIMEOUT_BATCH_SIZE);
        return orderMapper.selectList(
                        new LambdaQueryWrapper<Order>()
                                .select(Order::getId)
                                .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                                .lt(Order::getExpireTime, LocalDateTime.now())
                                .orderByAsc(Order::getExpireTime)
                                .last("LIMIT " + safeLimit))
                .stream()
                .map(Order::getId)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelExpiredOrder(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：status + expire_time 一起判定，与 pay 的更新在同一行锁上互斥
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                .lt(Order::getExpireTime, now)
                .set(Order::getStatus, ORDER_STATUS_CANCELLED)
                .set(Order::getCancelTime, now));
        if (rows == 0) {
            return false;
        }

        releaseOrderStock(orderMapper.selectById(orderId));
        log.info("订单超时取消, orderId={}", orderId);
        return true;
    }

    /** 回滚订单对应来源的库存 */
    private void releaseOrderStock(Order order) {
        if (order.getActivityId() == null) {
            releaseLockedStock(order.getId());
        } else {
            restoreActivityStock(order);
        }
    }

    /** 秒杀订单取消：可用库存 +n，且不突破活动总库存 */
    private void restoreActivityStock(Order order) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        int quantity = items.stream().mapToInt(OrderItem::getQuantity).sum();

        int updated = seckillActivityMapper.update(null,
                new LambdaUpdateWrapper<SeckillActivity>()
                        .eq(SeckillActivity::getId, order.getActivityId())
                        .apply("available_stock + {0} <= total_stock", quantity)
                        .setSql("available_stock = available_stock + " + quantity));
        if (updated == 0) {
            throw new BusinessException("活动库存数据异常，请联系客服");
        }

        // DB 回补成功后再加 Redis；失败只记日志，取消仍以 DB 为准
        seckillStockService.restoreStock(order.getActivityId(), quantity);
    }

    /** 取消/超时共用：available + n、locked - n，库存异常则整单回滚 */
    private void releaseLockedStock(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            int updated = productStockMapper.update(null,
                    new LambdaUpdateWrapper<ProductStock>()
                            .eq(ProductStock::getSkuId, item.getSkuId())
                            .ge(ProductStock::getLockedStock, item.getQuantity())
                            .setSql("available_stock = available_stock + " + item.getQuantity())
                            .setSql("locked_stock = locked_stock - " + item.getQuantity()));
            if (updated == 0) {
                throw new BusinessException("库存数据异常，请联系客服");
            }
        }
    }

    private OrderItemVO toItemVO(OrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        vo.setId(item.getId());
        vo.setSpuId(item.getSpuId());
        vo.setSkuId(item.getSkuId());
        vo.setProductName(item.getProductName());
        vo.setSpecDesc(item.getSpecDesc());
        vo.setImage(item.getImage());
        vo.setPrice(item.getPrice());
        vo.setQuantity(item.getQuantity());
        vo.setTotalAmount(item.getTotalAmount());
        return vo;
    }

    private OrderAddressVO toAddressVO(OrderAddress address) {
        OrderAddressVO vo = new OrderAddressVO();
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetail(address.getDetail());
        return vo;
    }

    /** 订单号：时间戳 + 4 位随机数，唯一索引兜底 */
    private String generateOrderNo(LocalDateTime now) {
        return now.format(ORDER_NO_TIME_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private UserAddressVO loadDefaultAddress(Long userId) {
        List<UserAddress> defaults = userAddressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        .eq(UserAddress::getIsDefault, ADDRESS_DEFAULT)
                        .orderByDesc(UserAddress::getCreateTime)
                        .last("LIMIT 1"));

        UserAddress address = defaults.isEmpty()
                ? userAddressMapper.selectList(
                        new LambdaQueryWrapper<UserAddress>()
                                .eq(UserAddress::getUserId, userId)
                                .orderByDesc(UserAddress::getCreateTime)
                                .last("LIMIT 1"))
                        .stream().findFirst().orElse(null)
                : defaults.get(0);

        if (address == null) {
            return null;
        }

        UserAddressVO vo = new UserAddressVO();
        vo.setId(address.getId());
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetail(address.getDetail());
        vo.setIsDefault(address.getIsDefault());
        return vo;
    }
}
