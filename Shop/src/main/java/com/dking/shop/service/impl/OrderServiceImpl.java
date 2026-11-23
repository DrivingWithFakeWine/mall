package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.shop.client.ProductClient;
import com.dking.shop.client.StockOpRejectedException;
import com.dking.shop.client.dto.SkuSnapshot;
import com.dking.shop.client.dto.StockItem;
import com.dking.shop.client.dto.StockOpRequest;
import com.dking.shop.client.dto.StockOpResult;
import com.dking.shop.common.Result;
import com.dking.shop.dto.DirectOrderSubmitDTO;
import com.dking.shop.dto.OrderSubmitDTO;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderAddress;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.exception.ServiceUnavailableException;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.OrderAddressMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.service.OrderService;
import com.dking.shop.service.SeckillBucketStockService;
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

/**
 * 订单服务。
 *
 * <h3>2026-11-22 起：库存写入改走商品服务</h3>
 *
 * <p>下单/支付/取消不再直接读写 {@code product_stock}，全部通过 {@link ProductClient}：</p>
 * <pre>
 * 下单：reserve(orderNo, items) → 本地事务建单 → 失败 release
 * 支付：本地事务改状态 → confirm + sales
 * 取消：本地事务改状态 → release
 * </pre>
 *
 * <p><b>为什么 reserve 在本地事务之前：</b>不能把远程调用包在本地事务里（会长时间占用数据库连接）。
 * 顺序上「先占库存再建单」，失败模式是「库存锁了但订单没建成」——对账释放，**宁可少卖不可卖空**。
 * 反过来的话会出现「订单已建但没库存」，用户看到下单成功又被取消。</p>
 *
 * <p><b>为什么支付/取消的远程调用放在事务之后：</b>订单状态是唯一真相，先落库。
 * 远程失败只记 ERROR，不做重试也不回滚状态——由对账任务补齐（少卖一会儿，但不会超卖）。</p>
 */
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

    /** 库存释放的最大尝试次数（含第一次） */
    private static final int RELEASE_MAX_ATTEMPTS = 3;

    /** 释放重试的退避基数：第 n 次失败后等 n × 200ms */
    private static final long RELEASE_BACKOFF_MILLIS = 200L;

    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private CartMapper cartMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private OrderAddressMapper orderAddressMapper;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private ProductClient productClient;

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

    // ------------------------------------------------------------------
    // 结算确认
    // ------------------------------------------------------------------

    @Override
    public OrderConfirmVO confirm(Long userId) {
        List<Cart> carts = loadSelectedCarts(userId);
        if (carts.isEmpty()) {
            throw new BusinessException("请先选择要结算的商品");
        }

        Map<Long, SkuSnapshot> skuMap = loadSkuSnapshots(carts);

        List<ConfirmItemVO> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Cart cart : carts) {
            SkuSnapshot sku = skuMap.get(cart.getSkuId());

            // 失效商品不进入结算（购物车页已提示过）
            if (!isSellable(sku) || sku.availableStock() < cart.getQuantity()) {
                continue;
            }

            BigDecimal subtotal = sku.price().multiply(BigDecimal.valueOf(cart.getQuantity()));

            ConfirmItemVO item = new ConfirmItemVO();
            item.setCartId(cart.getId());
            item.setSkuId(cart.getSkuId());
            item.setSpuId(sku.spuId());
            item.setName(sku.spuName());
            item.setSpecDesc(sku.specDesc());
            item.setImage(sku.image() != null ? sku.image() : sku.spuMainImage());
            item.setPrice(sku.price());
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

    // ------------------------------------------------------------------
    // 下单
    // ------------------------------------------------------------------

    @Override
    public OrderSubmitVO submit(Long userId, OrderSubmitDTO dto) {
        synchronized (lockOf(userId)) {
            // ① 事务外准备：地址、购物车、商品快照（远程）
            UserAddress address = loadAddress(userId, dto.getAddressId());
            List<Cart> carts = loadSelectedCarts(userId);
            if (carts.isEmpty()) {
                throw new BusinessException("请先选择要结算的商品");
            }
            Map<Long, SkuSnapshot> skuMap = loadSkuSnapshots(carts);
            validateCarts(carts, skuMap);

            String orderNo = generateOrderNo(LocalDateTime.now());
            List<StockItem> stockItems = cartStockItems(carts);

            // ② 远程预占：失败直接抛出，不建单
            try {
                reserveStock(orderNo, stockItems);
            } catch (StockOpRejectedException e) {
                // 明确的业务拒绝（库存不足等）：预占没有成功，**绝对不能释放**，
                // 否则会把同 SKU 其他订单锁定的库存放掉，造成超卖
                throw e;
            } catch (RuntimeException e) {
                // 结果未知（超时、连接中断）：reserve 可能已经成功，必须补偿释放
                releaseStockWithRetry(orderNo, stockItems, "预占结果未知，补偿释放");
                throw e;
            }

            // ③ 本地事务建单；失败补偿释放
            try {
                return transactionTemplate.execute(status ->
                        createOrder(userId, orderNo, address, carts, skuMap, dto.getRemark()));
            } catch (RuntimeException e) {
                releaseStockWithRetry(orderNo, stockItems, "建单失败补偿");
                throw e;
            }
        }
    }

    @Override
    public OrderSubmitVO submitDirect(Long userId, DirectOrderSubmitDTO dto) {
        // ① 事务外准备
        UserAddress address = loadAddress(userId, dto.getAddressId());
        SkuSnapshot sku = loadSkuSnapshot(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException("商品不存在");
        }
        if (!isSellable(sku)) {
            throw new BusinessException("商品已下架");
        }
        int quantity = dto.getQuantity();
        if (sku.availableStock() < quantity) {
            throw new BusinessException("库存不足：" + sku.spuName());
        }

        String orderNo = generateOrderNo(LocalDateTime.now());
        List<StockItem> stockItems = List.of(new StockItem(sku.skuId(), quantity));

        // ② 远程预占
        reserveStock(orderNo, stockItems);

        // ③ 本地建单
        try {
            return transactionTemplate.execute(status ->
                    createDirectOrder(userId, orderNo, address, sku, quantity, dto.getRemark()));
        } catch (RuntimeException e) {
            releaseStockWithRetry(orderNo, stockItems, "立即购买建单失败补偿");
            throw e;
        }
    }

    /** 建单事务：只碰订单域自己的表 */
    private OrderSubmitVO createOrder(Long userId, String orderNo, UserAddress address,
                                      List<Cart> carts, Map<Long, SkuSnapshot> skuMap, String remark) {
        LocalDateTime now = LocalDateTime.now();

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Cart cart : carts) {
            SkuSnapshot sku = skuMap.get(cart.getSkuId());
            totalAmount = totalAmount.add(sku.price().multiply(BigDecimal.valueOf(cart.getQuantity())));
        }

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setMerchantId(SINGLE_MERCHANT_ID);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount);
        order.setStatus(ORDER_STATUS_WAIT_PAY);
        order.setExpireTime(now.plusMinutes(PAY_TIMEOUT_MINUTES));
        order.setRemark(remark);
        orderMapper.insert(order);

        for (Cart cart : carts) {
            SkuSnapshot sku = skuMap.get(cart.getSkuId());
            insertOrderItem(order.getId(), orderNo, sku, cart.getQuantity());
        }

        insertAddressSnapshot(order.getId(), address);

        // 只清理本次下单的购物车项
        cartMapper.deleteBatchIds(carts.stream().map(Cart::getId).toList());

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

    private OrderSubmitVO createDirectOrder(Long userId, String orderNo, UserAddress address,
                                            SkuSnapshot sku, int quantity, String remark) {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal amount = sku.price().multiply(BigDecimal.valueOf(quantity));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setMerchantId(SINGLE_MERCHANT_ID);
        order.setTotalAmount(amount);
        order.setPayAmount(amount);
        order.setStatus(ORDER_STATUS_WAIT_PAY);
        order.setExpireTime(now.plusMinutes(PAY_TIMEOUT_MINUTES));
        order.setRemark(remark);
        orderMapper.insert(order);

        insertOrderItem(order.getId(), orderNo, sku, quantity);
        insertAddressSnapshot(order.getId(), address);

        log.info("立即购买下单成功, userId={}, orderNo={}, skuId={}, quantity={}, payAmount={}",
                userId, orderNo, sku.skuId(), quantity, amount);

        OrderSubmitVO vo = new OrderSubmitVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(orderNo);
        vo.setPayAmount(amount);
        vo.setStatus(ORDER_STATUS_WAIT_PAY);
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    /** 订单明细是价格快照：写死当时的名称、规格、图片、单价 */
    private void insertOrderItem(Long orderId, String orderNo, SkuSnapshot sku, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setOrderNo(orderNo);
        item.setSpuId(sku.spuId());
        item.setSkuId(sku.skuId());
        item.setProductName(sku.spuName());
        item.setSpecDesc(sku.specDesc());
        item.setImage(sku.image() != null ? sku.image() : sku.spuMainImage());
        item.setPrice(sku.price());
        item.setQuantity(quantity);
        item.setTotalAmount(sku.price().multiply(BigDecimal.valueOf(quantity)));
        orderItemMapper.insert(item);
    }

    private void insertAddressSnapshot(Long orderId, UserAddress address) {
        OrderAddress orderAddress = new OrderAddress();
        orderAddress.setOrderId(orderId);
        orderAddress.setReceiverName(address.getReceiverName());
        orderAddress.setReceiverPhone(address.getReceiverPhone());
        orderAddress.setProvince(address.getProvince());
        orderAddress.setCity(address.getCity());
        orderAddress.setDistrict(address.getDistrict());
        orderAddress.setDetail(address.getDetail());
        orderAddressMapper.insert(orderAddress);
    }

    // ------------------------------------------------------------------
    // 列表 / 详情
    // ------------------------------------------------------------------

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

        List<OrderItem> items = loadOrderItems(orderId);

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

    // ------------------------------------------------------------------
    // 支付 / 取消
    // ------------------------------------------------------------------

    @Override
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

        // ① 状态迁移：条件更新，影响 0 行说明已被并发修改（短事务，不包远程调用）
        Integer rows = transactionTemplate.execute(status -> orderMapper.update(null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId)
                        .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                        .gt(Order::getExpireTime, now)
                        .set(Order::getStatus, ORDER_STATUS_PAID)
                        .set(Order::getPayTime, now)));
        if (rows == null || rows == 0) {
            throw new BusinessException("订单状态不允许支付");
        }

        // ② 状态已落库，再做库存确认和销量；失败只告警，由对账补齐
        List<StockItem> stockItems = itemStockItems(loadOrderItems(orderId));
        if (order.getActivityId() == null) {
            confirmStockQuietly(order.getOrderNo(), stockItems);
        }
        salesStockQuietly(order.getOrderNo(), stockItems);

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
        Integer rows = transactionTemplate.execute(status -> orderMapper.update(null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getUserId, userId)
                        .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                        .set(Order::getStatus, ORDER_STATUS_CANCELLED)
                        .set(Order::getCancelTime, now)));
        if (rows == null || rows == 0) {
            throw new BusinessException("订单状态不允许取消");
        }

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
    public boolean cancelExpiredOrder(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：status + expire_time 一起判定，与 pay 的更新在同一行锁上互斥
        Integer rows = transactionTemplate.execute(status -> orderMapper.update(null,
                new LambdaUpdateWrapper<Order>()
                        .eq(Order::getId, orderId)
                        .eq(Order::getStatus, ORDER_STATUS_WAIT_PAY)
                        .lt(Order::getExpireTime, now)
                        .set(Order::getStatus, ORDER_STATUS_CANCELLED)
                        .set(Order::getCancelTime, now)));
        if (rows == null || rows == 0) {
            return false;
        }

        releaseOrderStock(orderMapper.selectById(orderId));
        log.info("订单超时取消, orderId={}", orderId);
        return true;
    }

    /** 按订单来源回滚库存：普通订单归还商品服务，秒杀订单归还活动桶 */
    private void releaseOrderStock(Order order) {
        if (order == null) {
            return;
        }
        List<StockItem> items = itemStockItems(loadOrderItems(order.getId()));
        if (order.getActivityId() == null) {
            releaseStockWithRetry(order.getOrderNo(), items, "取消/超时回补");
        } else {
            restoreActivityStock(order, items);
        }
    }

    /** 秒杀订单取消：回补到下单时使用的桶，不突破该桶总量 */
    private void restoreActivityStock(Order order, List<StockItem> items) {
        int quantity = items.stream().mapToInt(StockItem::quantity).sum();
        try {
            boolean restored = bucketStockService.restore(order.getActivityId(), order.getStockBucket(), quantity);
            if (!restored) {
                log.error("活动库存回补失败，等待对账, orderNo={}, activityId={}, bucket={}",
                        order.getOrderNo(), order.getActivityId(), order.getStockBucket());
                return;
            }
            // DB 回补成功后再加 Redis；失败只记日志，取消仍以 DB 为准
            seckillStockService.restoreStock(order.getActivityId(), quantity);
        } catch (Exception e) {
            log.error("活动库存回补异常，等待对账, orderNo={}", order.getOrderNo(), e);
        }
    }

    // ------------------------------------------------------------------
    // 商品服务调用（RPC）
    // ------------------------------------------------------------------

    private boolean isSellable(SkuSnapshot sku) {
        return sku != null
                && SKU_STATUS_ON.equals(sku.skuStatus())
                && SPU_STATUS_ON.equals(sku.spuStatus());
    }

    private void validateCarts(List<Cart> carts, Map<Long, SkuSnapshot> skuMap) {
        for (Cart cart : carts) {
            SkuSnapshot sku = skuMap.get(cart.getSkuId());
            if (!isSellable(sku)) {
                throw new BusinessException("商品已下架，请返回购物车调整");
            }
            if (sku.availableStock() < cart.getQuantity()) {
                throw new BusinessException("库存不足：" + sku.spuName());
            }
        }
    }

    /** 预占库存；库存不足转成用户可读的提示，但仍保留错误码，便于上层区分「确定失败」和「结果未知」 */
    private void reserveStock(String orderNo, List<StockItem> items) {
        try {
            requireSuccess(productClient.reserve(new StockOpRequest(orderNo, items)), "预占库存");
        } catch (StockOpRejectedException e) {
            if ("INSUFFICIENT_STOCK".equals(e.code())) {
                // 保留 StockOpRejectedException 类型：submit 靠它判断「确定没占上，不需要补偿」
                throw new StockOpRejectedException(e.code(), "库存不足，请返回购物车调整");
            }
            // 其他错误码（数量冲突、状态非法）通常是调用顺序问题，直接抛出让上层记录
            throw e;
        }
    }

    /**
     * 释放库存，最多尝试 {@value #RELEASE_MAX_ATTEMPTS} 次。
     *
     * <p><b>为什么需要重试：</b>释放是「取消订单」的最后一步，订单状态已经落库。
     * 如果因为一次网络抖动就放弃，库存会一直被锁着（少卖），而且没有任何补偿入口。
     * 三次都失败才认定需要人工/对账介入。</p>
     *
     * <p><b>为什么业务拒绝不重试：</b>{@code ALREADY_RELEASED} 说明之前已经释放过（幂等成功），
     * {@code NOT_RESERVED} 说明这单本来就没占库存，重试不会改变结果。</p>
     *
     * <p>方法本身不抛异常：调用点是在 catch 块里做补偿，不能覆盖原始异常。</p>
     */
    private void releaseStockWithRetry(String orderNo, List<StockItem> items, String reason) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (int attempt = 1; attempt <= RELEASE_MAX_ATTEMPTS; attempt++) {
            try {
                requireSuccess(productClient.release(new StockOpRequest(orderNo, items)), "释放库存");
                log.info("释放库存成功, orderNo={}, reason={}, attempt={}/{}",
                        orderNo, reason, attempt, RELEASE_MAX_ATTEMPTS);
                return;
            } catch (StockOpRejectedException e) {
                // 业务拒绝：重试无意义，按「已释放」处理
                log.warn("释放库存被拒绝，不再重试, orderNo={}, reason={}, code={}, attempt={}/{}",
                        orderNo, reason, e.code(), attempt, RELEASE_MAX_ATTEMPTS);
                return;
            } catch (Exception e) {
                log.warn("释放库存失败, orderNo={}, reason={}, attempt={}/{}, err={}",
                        orderNo, reason, attempt, RELEASE_MAX_ATTEMPTS, e.toString());
                if (attempt < RELEASE_MAX_ATTEMPTS) {
                    sleepQuietly(RELEASE_BACKOFF_MILLIS * attempt);
                }
            }
        }

        log.error("释放库存连续 {} 次失败，等待对账, orderNo={}, reason={}",
                RELEASE_MAX_ATTEMPTS, orderNo, reason);
    }

    /** 退避等待；被中断就恢复中断标记并继续下一次尝试 */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void confirmStockQuietly(String orderNo, List<StockItem> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            productClient.confirm(new StockOpRequest(orderNo, items));
        } catch (Exception e) {
            log.error("确认库存失败，等待对账, orderNo={}", orderNo, e);
        }
    }

    private void salesStockQuietly(String orderNo, List<StockItem> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            productClient.sales(new StockOpRequest(orderNo, items));
        } catch (Exception e) {
            log.error("累加销量失败，等待对账, orderNo={}", orderNo, e);
        }
    }

    private void requireSuccess(Result<?> result, String action) {
        if (result == null || result.getCode() == null || result.getCode() != 200) {
            String message = result == null ? "空响应" : result.getMessage();
            throw new ServiceUnavailableException(action + "失败: " + message);
        }
    }



    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    private List<Cart> loadSelectedCarts(Long userId) {
        return cartMapper.selectList(
                new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getUserId, userId)
                        .eq(Cart::getSelected, CART_SELECTED)
                        .orderByDesc(Cart::getCreateTime)
                        .orderByDesc(Cart::getId));
    }

    private UserAddress loadAddress(Long userId, Long addressId) {
        UserAddress address = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, addressId)
                        .eq(UserAddress::getUserId, userId));
        if (address == null) {
            throw new BusinessException("收货地址不存在");
        }
        return address;
    }

    private Map<Long, SkuSnapshot> loadSkuSnapshots(List<Cart> carts) {
        List<Long> skuIds = carts.stream().map(Cart::getSkuId).distinct().toList();
        return productClient.batchSkus(skuIds).getData().stream()
                .collect(Collectors.toMap(SkuSnapshot::skuId, Function.identity()));
    }

    private SkuSnapshot loadSkuSnapshot(Long skuId) {
        List<SkuSnapshot> list = productClient.batchSkus(List.of(skuId)).getData();
        return list.isEmpty() ? null : list.get(0);
    }

    private List<OrderItem> loadOrderItems(Long orderId) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId)
                        .orderByAsc(OrderItem::getId));
    }

    private List<StockItem> cartStockItems(List<Cart> carts) {
        return carts.stream()
                .map(cart -> new StockItem(cart.getSkuId(), cart.getQuantity()))
                .toList();
    }

    private List<StockItem> itemStockItems(List<OrderItem> items) {
        return items.stream()
                .map(item -> new StockItem(item.getSkuId(), item.getQuantity()))
                .toList();
    }

    // ------------------------------------------------------------------
    // VO 组装
    // ------------------------------------------------------------------

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
