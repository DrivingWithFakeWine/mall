package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.shop.dto.SeckillOrderDTO;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.OrderAddress;
import com.dking.shop.entity.OrderItem;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.exception.SeckillSoldOutException;
import com.dking.shop.mapper.OrderAddressMapper;
import com.dking.shop.mapper.OrderItemMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.SeckillActivityMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.service.SeckillService;
import com.dking.shop.service.SeckillStockService;
import com.dking.shop.service.support.ProductSearchSupport;
import com.dking.shop.service.support.SeckillActivityCache;
import com.dking.shop.service.support.SeckillActivitySnapshot;
import com.dking.shop.util.LikeUtils;
import com.dking.shop.vo.OrderSubmitVO;
import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.SeckillActivityDetailVO;
import com.dking.shop.vo.SeckillActivityListItemVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    private static final Integer ACTIVITY_STATUS_ONLINE = 1;
    private static final Integer SKU_STATUS_ON = 1;
    private static final Integer SPU_STATUS_ON = 1;
    private static final Integer ORDER_STATUS_WAIT_PAY = 0;
    private static final Long SINGLE_MERCHANT_ID = 0L;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int PAY_TIMEOUT_MINUTES = 30;
    private static final int PHASE_NOT_STARTED = 0;
    private static final int PHASE_ONGOING = 1;
    private static final int PHASE_ENDED = 2;
    private static final int PHASE_SOLD_OUT = 3;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private OrderAddressMapper orderAddressMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillActivityCache seckillActivityCache;

    @Resource
    private ProductSearchSupport productSearchSupport;

    @Override
    public PageVO<SeckillActivityListItemVO> list(int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDateTime now = LocalDateTime.now();
        String kw = LikeUtils.normalize(keyword);

        LambdaQueryWrapper<SeckillActivity> wrapper = new LambdaQueryWrapper<SeckillActivity>()
                .eq(SeckillActivity::getStatus, ACTIVITY_STATUS_ONLINE)
                .gt(SeckillActivity::getEndTime, now)
                .orderByAsc(SeckillActivity::getStartTime)
                .orderByAsc(SeckillActivity::getId);

        if (kw != null) {
            // 活动名直接匹配；商品名/副标题/分类名匹配先查到 spuId，再回查活动
            List<Long> matchedSpuIds = productSearchSupport.spuIdsByKeyword(kw, 200);

            wrapper.and(w -> {
                w.like(SeckillActivity::getName, kw);
                if (!matchedSpuIds.isEmpty()) {
                    w.or().in(SeckillActivity::getSpuId, matchedSpuIds);
                }
            });
        }

        Page<SeckillActivity> result = seckillActivityMapper.selectPage(
                new Page<>(safePage, safeSize), wrapper);

        PageVO<SeckillActivityListItemVO> pageVO = new PageVO<>();
        pageVO.setTotal(Math.toIntExact(result.getTotal()));
        if (result.getRecords().isEmpty()) {
            pageVO.setRecords(List.of());
            return pageVO;
        }

        Map<Long, ProductSpu> spuMap = loadSpus(result.getRecords());
        Map<Long, ProductSku> skuMap = loadSkus(result.getRecords());
        pageVO.setRecords(result.getRecords().stream()
                .map(activity -> toListItem(activity, spuMap, skuMap, now))
                .toList());

        log.info("查询秒杀活动列表, page={}, size={}, keyword={}, total={}",
                safePage, safeSize, keyword, result.getTotal());
        return pageVO;
    }

    @Override
    public SeckillActivityDetailVO detail(Long activityId) {
        SeckillActivityCache.Lookup lookup = seckillActivityCache.lookup(activityId);
        if (lookup.missing()) {
            throw new NotFoundException("秒杀活动不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (lookup.hit()) {
            SeckillActivitySnapshot snapshot = lookup.snapshot();
            if (!ACTIVITY_STATUS_ONLINE.equals(snapshot.status())) {
                throw new NotFoundException("秒杀活动不存在");
            }
            int stock = resolveStock(activityId, null);
            SeckillActivityDetailVO vo = buildDetail(snapshot, stock, now);
            log.info("查询秒杀活动详情, activityId={}, phase={}, cached=true",
                    activityId, vo.getPhase());
            return vo;
        }

        // 存在redis和 DB 数据不一致，待以后改进
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        if (activity == null || !ACTIVITY_STATUS_ONLINE.equals(activity.getStatus())) {
            seckillActivityCache.putMissing(activityId);
            throw new NotFoundException("秒杀活动不存在");
        }

        ProductSku sku = productSkuMapper.selectById(activity.getSkuId());
        ProductSpu spu = sku == null ? null : productSpuMapper.selectById(sku.getSpuId());
        SeckillActivitySnapshot snapshot = toSnapshot(activity, spu, sku);
        seckillActivityCache.put(snapshot);

        int stock = resolveStock(activityId, activity.getAvailableStock());
        SeckillActivityDetailVO vo = buildDetail(snapshot, stock, now);
        log.info("查询秒杀活动详情, activityId={}, phase={}, cached=false",
                activityId, vo.getPhase());
        return vo;
    }

    /** 库存优先读 Redis；Redis 不可用或 key 缺失时回退 DB，详情接口不因缓存故障失败。 */
    private int resolveStock(Long activityId, Integer dbFallback) {
        try {
            Integer redisStock = seckillStockService.currentStock(activityId);
            if (redisStock != null) {
                return redisStock;
            }
        } catch (RuntimeException e) {
            log.warn("读取 Redis 活动库存失败, activityId={}", activityId, e);
        }
        if (dbFallback != null) {
            return dbFallback;
        }
        SeckillActivity latest = seckillActivityMapper.selectById(activityId);
        if (latest == null || !ACTIVITY_STATUS_ONLINE.equals(latest.getStatus())) {
            throw new NotFoundException("秒杀活动不存在");
        }
        return latest.getAvailableStock();
    }

    private SeckillActivityDetailVO buildDetail(SeckillActivitySnapshot snapshot,
                                                int availableStock, LocalDateTime now) {
        SeckillActivityDetailVO vo = new SeckillActivityDetailVO();
        vo.setId(snapshot.id());
        vo.setSpuId(snapshot.spuId());
        vo.setSkuId(snapshot.skuId());
        vo.setName(snapshot.name());
        vo.setProductName(snapshot.productName());
        vo.setImage(snapshot.image());
        vo.setSpecDesc(snapshot.specDesc());
        vo.setSeckillPrice(snapshot.seckillPrice());
        vo.setOriginalPrice(snapshot.originalPrice());
        vo.setTotalStock(snapshot.totalStock());
        vo.setAvailableStock(availableStock);
        vo.setStartTime(snapshot.startTime());
        vo.setEndTime(snapshot.endTime());
        vo.setPhase(phaseOf(snapshot.startTime(), snapshot.endTime(), availableStock, now));
        vo.setPerUserLimit(snapshot.perUserLimit());
        vo.setServerTime(now);
        return vo;
    }

    private SeckillActivitySnapshot toSnapshot(SeckillActivity activity, ProductSpu spu, ProductSku sku) {
        return new SeckillActivitySnapshot(
                activity.getId(),
                activity.getSpuId(),
                activity.getSkuId(),
                activity.getName(),
                activity.getSeckillPrice(),
                activity.getTotalStock(),
                activity.getPerUserLimit(),
                activity.getStartTime(),
                activity.getEndTime(),
                activity.getStatus(),
                spu != null ? spu.getName() : activity.getName(),
                sku != null && sku.getImage() != null
                        ? sku.getImage()
                        : (spu != null ? spu.getMainImage() : null),
                sku != null ? sku.getSpecDesc() : null,
                sku != null ? sku.getPrice() : null);
    }

    @Override
    public OrderSubmitVO submit(Long userId, Long activityId, SeckillOrderDTO dto) {
        // 事务外完成参数校验与 Redis 预扣：输家不占用 DB 连接
        UserAddress address = loadAddress(userId, dto.getAddressId());
        SeckillActivity activity = loadOnlineActivity(activityId);

        SeckillStockService.ReserveResult result = seckillStockService.reserve(activity, userId);
        switch (result) {
            case DUPLICATE -> throw new BusinessException("您已参与过该活动，请勿重复提交");
            case SOLD_OUT -> throw new SeckillSoldOutException();
            case NOT_STARTED -> throw new BusinessException("活动尚未开始");
            case ENDED -> throw new BusinessException("活动已结束");
            case RESERVED -> {
                // 继续进入 DB 事务
            }
        }

        try {
            return transactionTemplate.execute(status -> doSubmit(userId, activity, address, dto));
        } catch (SeckillSoldOutException e) {
            // DB 库存已为 0，Redis 预扣结果与 DB 一致，不能再归还
            throw e;
        } catch (DuplicateKeyException e) {
            // 唯一键兜底：归还库存，但保留用户占位，避免反复撞库
            releaseQuietly(activityId, userId, true);
            throw new BusinessException("您已参与过该活动，请勿重复提交");
        } catch (RuntimeException e) {
            // 地址/商品/DB 任一步失败：归还预扣并允许重试
            releaseQuietly(activityId, userId, false);
            throw e;
        }
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

    private SeckillActivity loadOnlineActivity(Long activityId) {
        SeckillActivity activity = seckillActivityMapper.selectById(activityId);
        if (activity == null || !ACTIVITY_STATUS_ONLINE.equals(activity.getStatus())) {
            throw new NotFoundException("秒杀活动不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BusinessException("活动尚未开始");
        }
        if (!now.isBefore(activity.getEndTime())) {
            throw new BusinessException("活动已结束");
        }
        return activity;
    }

    private OrderSubmitVO doSubmit(Long userId, SeckillActivity activity,
                                   UserAddress address, SeckillOrderDTO dto) {
        Long activityId = activity.getId();
        ProductSku sku = productSkuMapper.selectById(activity.getSkuId());
        ProductSpu spu = sku == null ? null : productSpuMapper.selectById(sku.getSpuId());
        if (sku == null || spu == null) {
            throw new BusinessException("商品不存在");
        }
        if (!SKU_STATUS_ON.equals(sku.getStatus()) || !SPU_STATUS_ON.equals(spu.getStatus())) {
            throw new BusinessException("商品已下架");
        }

        // 原子扣活动库存：数量固定 1，售罄/活动状态变化都会影响 0 行
        LocalDateTime now = LocalDateTime.now();
        int rows = seckillActivityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .eq(SeckillActivity::getId, activityId)
                .eq(SeckillActivity::getStatus, ACTIVITY_STATUS_ONLINE)
                .le(SeckillActivity::getStartTime, now)
                .gt(SeckillActivity::getEndTime, now)
                .ge(SeckillActivity::getAvailableStock, 1)
                .setSql("available_stock = available_stock - 1"));
        if (rows == 0) {
            SeckillActivity latest = seckillActivityMapper.selectById(activityId);
            if (latest == null || !ACTIVITY_STATUS_ONLINE.equals(latest.getStatus())) {
                throw new BusinessException("秒杀活动不存在");
            }
            if (now.isBefore(latest.getStartTime())) {
                throw new BusinessException("活动尚未开始");
            }
            if (!now.isBefore(latest.getEndTime())) {
                throw new BusinessException("活动已结束");
            }
            if (latest.getAvailableStock() != null && latest.getAvailableStock() <= 0) {
                throw new SeckillSoldOutException();
            }
            throw new BusinessException("活动商品已售罄");
        }

        // 生成订单：复用 orders，activity_id 标记来源
        BigDecimal amount = activity.getSeckillPrice();
        String orderNo = "SK" + IdWorker.getIdStr();

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setMerchantId(SINGLE_MERCHANT_ID);
        order.setActivityId(activityId);
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
        item.setPrice(amount);
        item.setQuantity(1);
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

        log.info("秒杀下单成功, userId={}, activityId={}, orderNo={}, payAmount={}",
                userId, activityId, orderNo, amount);

        OrderSubmitVO vo = new OrderSubmitVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(orderNo);
        vo.setPayAmount(amount);
        vo.setStatus(ORDER_STATUS_WAIT_PAY);
        vo.setExpireTime(order.getExpireTime());
        return vo;
    }

    private void releaseQuietly(Long activityId, Long userId, boolean markUser) {
        try {
            seckillStockService.release(activityId, userId, markUser);
        } catch (RuntimeException e) {
            log.error("Redis 秒杀预扣回滚失败, activityId={}, userId={}", activityId, userId, e);
        }
    }

    private Map<Long, ProductSpu> loadSpus(List<SeckillActivity> activities) {
        List<Long> spuIds = activities.stream()
                .map(SeckillActivity::getSpuId)
                .distinct()
                .toList();
        if (spuIds.isEmpty()) {
            return Map.of();
        }
        return productSpuMapper.selectBatchIds(spuIds).stream()
                .collect(Collectors.toMap(ProductSpu::getId, Function.identity()));
    }

    private Map<Long, ProductSku> loadSkus(List<SeckillActivity> activities) {
        List<Long> skuIds = activities.stream()
                .map(SeckillActivity::getSkuId)
                .distinct()
                .toList();
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return productSkuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, Function.identity()));
    }

    private SeckillActivityListItemVO toListItem(SeckillActivity activity,
                                                 Map<Long, ProductSpu> spuMap,
                                                 Map<Long, ProductSku> skuMap,
                                                 LocalDateTime now) {
        SeckillActivityListItemVO vo = new SeckillActivityListItemVO();
        fillItemFields(vo, activity,
                spuMap.get(activity.getSpuId()), skuMap.get(activity.getSkuId()), now);
        return vo;
    }

    private void fillItemFields(SeckillActivityListItemVO vo,
                                SeckillActivity activity,
                                ProductSpu spu,
                                ProductSku sku,
                                LocalDateTime now) {
        vo.setId(activity.getId());
        vo.setSpuId(activity.getSpuId());
        vo.setSkuId(activity.getSkuId());
        vo.setName(activity.getName());
        vo.setProductName(spu != null ? spu.getName() : activity.getName());
        vo.setImage(sku != null && sku.getImage() != null
                ? sku.getImage()
                : (spu != null ? spu.getMainImage() : null));
        vo.setSpecDesc(sku != null ? sku.getSpecDesc() : null);
        vo.setSeckillPrice(activity.getSeckillPrice());
        vo.setOriginalPrice(sku != null ? sku.getPrice() : null);
        vo.setTotalStock(activity.getTotalStock());
        vo.setAvailableStock(activity.getAvailableStock());
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setPhase(phaseOf(activity, now));
    }

    private int phaseOf(SeckillActivity activity, LocalDateTime now) {
        return phaseOf(activity.getStartTime(), activity.getEndTime(),
                activity.getAvailableStock(), now);
    }

    private int phaseOf(LocalDateTime startTime, LocalDateTime endTime,
                        int availableStock, LocalDateTime now) {
        if (now.isBefore(startTime)) {
            return PHASE_NOT_STARTED;
        }
        if (!now.isBefore(endTime)) {
            return PHASE_ENDED;
        }
        if (availableStock <= 0) {
            return PHASE_SOLD_OUT;
        }
        return PHASE_ONGOING;
    }
}
