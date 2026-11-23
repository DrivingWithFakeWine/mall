package com.dking.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dking.marketing.dto.SeckillOrderCommand;
import com.dking.marketing.dto.SeckillOrderDTO;
import com.dking.marketing.dto.SeckillOrderMessage;
import com.dking.marketing.client.ProductClient;
import com.dking.marketing.client.dto.SkuSnapshot;
import com.dking.marketing.entity.SeckillActivity;
import com.dking.marketing.exception.BusinessException;
import com.dking.marketing.exception.NotFoundException;
import com.dking.marketing.exception.SeckillSoldOutException;
import com.dking.marketing.exception.ServiceUnavailableException;
import com.dking.marketing.mapper.SeckillActivityMapper;
import com.dking.marketing.service.SeckillBucketStockService;
import com.dking.marketing.service.SeckillOrderPublisher;
import com.dking.marketing.service.SeckillService;
import com.dking.marketing.service.SeckillStockService;
import com.dking.marketing.service.support.SeckillActivityCache;
import com.dking.marketing.service.support.SeckillActivitySnapshot;
import com.dking.marketing.service.support.SeckillOrderResultStore;
import com.dking.marketing.util.LikeUtils;
import com.dking.marketing.vo.OrderSubmitVO;
import com.dking.marketing.vo.PageVO;
import com.dking.marketing.vo.SeckillActivityDetailVO;
import com.dking.marketing.vo.SeckillActivityListItemVO;
import com.dking.marketing.vo.SeckillOrderSubmitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    private static final int MAX_PAGE_SIZE = 50;
    private static final int PHASE_NOT_STARTED = 0;
    private static final int PHASE_ONGOING = 1;
    private static final int PHASE_ENDED = 2;
    private static final int PHASE_SOLD_OUT = 3;

    @Resource
    private SeckillActivityMapper seckillActivityMapper;

    @Resource
    private ProductClient productClient;

    @Resource
    private SeckillStockService seckillStockService;

    @Resource
    private SeckillActivityCache seckillActivityCache;

    @Resource
    private SeckillOrderResultStore orderResultStore;

    @Resource
    private SeckillBucketStockService bucketStockService;

    @Resource
    private ObjectProvider<SeckillOrderPublisher> orderPublisherProvider;

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
            List<Long> matchedSpuIds = productClient.searchSpuIds(kw, 200).getData();

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

        Map<Long, SkuSnapshot> skuMap = loadSkus(result.getRecords());
        Map<Long, Integer> stockMap = bucketStockService.totalAvailable(
                result.getRecords().stream().map(SeckillActivity::getId).toList());
        pageVO.setRecords(result.getRecords().stream()
                .map(activity -> toListItem(activity, skuMap, now, stockMap))
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
            int stock = resolveStock(activityId);
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

        SkuSnapshot sku = loadSku(activity.getSkuId());
        SeckillActivitySnapshot snapshot = toSnapshot(activity, sku);
        seckillActivityCache.put(snapshot);

        int stock = resolveStock(activityId);
        SeckillActivityDetailVO vo = buildDetail(snapshot, stock, now);
        log.info("查询秒杀活动详情, activityId={}, phase={}, cached=false",
                activityId, vo.getPhase());
        return vo;
    }

    /** 库存优先读 Redis；Redis 不可用或 key 缺失时回退桶汇总，详情接口不因缓存故障失败。 */
    private int resolveStock(Long activityId) {
        try {
            Integer redisStock = seckillStockService.currentStock(activityId);
            if (redisStock != null) {
                return redisStock;
            }
        } catch (RuntimeException e) {
            log.warn("读取 Redis 活动库存失败, activityId={}", activityId, e);
        }
        SeckillActivity latest = seckillActivityMapper.selectById(activityId);
        if (latest == null) {
            return 0;
        }
        // 首次读取时桶可能还没建，先确保再汇总
        bucketStockService.ensure(latest);
        return bucketStockService.totalAvailable(activityId);
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

    private SeckillActivitySnapshot toSnapshot(SeckillActivity activity, SkuSnapshot sku) {
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
                sku != null && sku.spuName() != null ? sku.spuName() : activity.getName(),
                sku != null && sku.image() != null ? sku.image() : (sku == null ? null : sku.spuMainImage()),
                sku != null ? sku.specDesc() : null,
                sku != null ? sku.price() : null);
    }

    /**
     * 下单入口：Redis 闸门 → 校验商品 → 扣活动桶 → 发布建单消息。
     *
     * <p>营销是活动库存的唯一持有者，桶必须在发消息之前扣掉；交易服务只负责落库。
     * 返回 PENDING + token，前端轮询 {@code /seckill/order/result}。</p>
     */
    @Override
    public SeckillOrderSubmitVO submitOrder(Long userId, Long activityId, SeckillOrderDTO dto) {
        SeckillActivity activity = loadOnlineActivity(activityId);
        bucketStockService.ensure(activity);
        reserveOrThrow(activity, userId);

        SkuSnapshot sku = loadSku(activity.getSkuId());
        if (sku == null) {
            releaseQuietly(activityId, userId, false);
            throw new BusinessException("商品不存在");
        }
        if (!SKU_STATUS_ON.equals(sku.skuStatus()) || !SPU_STATUS_ON.equals(sku.spuStatus())) {
            releaseQuietly(activityId, userId, false);
            throw new BusinessException("商品已下架");
        }

        int bucketNo = bucketStockService.deduct(activityId, userId);
        if (bucketNo < 0) {
            // 桶已扣空：Redis 预扣必须还回去，否则两个库存源对不上
            releaseQuietly(activityId, userId, false);
            throw soldOut(activityId);
        }

        String token = IdWorker.getIdStr();
        SeckillOrderCommand command = new SeckillOrderCommand(
                userId, activityId, bucketNo, token, dto.getAddressId(),
                sku.spuId(), sku.skuId(), sku.spuName(), sku.specDesc(),
                sku.image() != null ? sku.image() : sku.spuMainImage(),
                activity.getSeckillPrice(), dto.getRemark());

        // 先写 PENDING、再发消息。
        // 反过来的话，消费者可能在 publish() 返回之前就把 SUCCESS 写进 Redis，
        // 随后这行 PENDING 会把它覆盖掉（write-after-read），前端只能一直轮询到超时。
        orderResultStore.markPending(token, userId);

        SeckillOrderPublisher publisher = orderPublisherProvider.getIfAvailable();
        if (publisher == null) {
            orderResultStore.remove(token);
            restoreQuietly(activity, bucketNo);
            releaseQuietly(activityId, userId, false);
            throw new ServiceUnavailableException("秒杀队列未启用，请稍后重试");
        }
        try {
            publisher.publish(new SeckillOrderMessage(token, command, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            // 入队失败：占位、桶、Redis 预扣三样都要回滚，否则「扣了库存但没有订单」
            orderResultStore.remove(token);
            restoreQuietly(activity, bucketNo);
            releaseQuietly(activityId, userId, false);
            log.error("秒杀消息入队失败, activityId={}, userId={}", activityId, userId, e);
            throw new ServiceUnavailableException("秒杀服务暂不可用，请稍后重试");
        }

        return SeckillOrderSubmitVO.pending(token);
    }

    @Override
    public SeckillOrderSubmitVO orderResult(Long userId, String token) {
        SeckillOrderResultStore.StoredResult stored = orderResultStore.get(token);
        if (stored == null) {
            // 结果只在 Redis：营销不读订单域的数据，过期就是过期
            throw new NotFoundException("订单不存在或结果已过期");
        }
        if (!String.valueOf(userId).equals(stored.userId())) {
            throw new NotFoundException("订单不存在");
        }
        SeckillOrderSubmitVO vo = new SeckillOrderSubmitVO();
        vo.setToken(token);
        vo.setStatus(stored.status());
        vo.setOrderId(stored.orderId());
        vo.setOrderNo(stored.orderNo());
        vo.setPayAmount(stored.payAmount());
        vo.setExpireTime(stored.expireTime());
        vo.setMessage(stored.message());
        return vo;
    }

    /** 桶扣空：区分「活动没了 / 没开始 / 结束了 / 卖完了」 */
    private RuntimeException soldOut(Long activityId) {
        SeckillActivity latest = seckillActivityMapper.selectById(activityId);
        if (latest == null || !ACTIVITY_STATUS_ONLINE.equals(latest.getStatus())) {
            return new BusinessException("秒杀活动不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(latest.getStartTime())) {
            return new BusinessException("活动尚未开始");
        }
        if (!now.isBefore(latest.getEndTime())) {
            return new BusinessException("活动已结束");
        }
        return new SeckillSoldOutException();
    }

    /** 入队失败：把刚扣掉的桶还回去 */
    private void restoreQuietly(SeckillActivity activity, int bucketNo) {
        try {
            bucketStockService.restore(activity.getId(), bucketNo, 1);
        } catch (RuntimeException e) {
            log.error("活动桶回补失败，等待对账, activityId={}, bucket={}", activity.getId(), bucketNo, e);
        }
    }
    /** Redis 闸门：预扣活动库存 + 用户占位，这一步挡住绝大多数并发热点 */
    private void reserveOrThrow(SeckillActivity activity, Long userId) {
        SeckillStockService.ReserveResult result = seckillStockService.reserve(activity, userId);
        switch (result) {
            case DUPLICATE -> throw new BusinessException("您已参与过该活动，请勿重复提交");
            case SOLD_OUT -> throw new SeckillSoldOutException();
            case NOT_STARTED -> throw new BusinessException("活动尚未开始");
            case ENDED -> throw new BusinessException("活动已结束");
            case RESERVED -> {
                // 继续
            }
        }
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

    private void releaseQuietly(Long activityId, Long userId, boolean markUser) {
        try {
            seckillStockService.release(activityId, userId, markUser);
        } catch (RuntimeException e) {
            log.error("Redis 秒杀预扣回滚失败, activityId={}, userId={}", activityId, userId, e);
        }
    }

    /** 一次批量查，key 是 skuId */
    private Map<Long, SkuSnapshot> loadSkus(List<SeckillActivity> activities) {
        List<Long> skuIds = activities.stream()
                .map(SeckillActivity::getSkuId)
                .distinct()
                .toList();
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return productClient.batchSkus(skuIds).getData().stream()
                .collect(Collectors.toMap(SkuSnapshot::skuId, Function.identity()));
    }

    private SkuSnapshot loadSku(Long skuId) {
        List<SkuSnapshot> list = productClient.batchSkus(List.of(skuId)).getData();
        return list.isEmpty() ? null : list.get(0);
    }

    private SeckillActivityListItemVO toListItem(SeckillActivity activity,
                                                 Map<Long, SkuSnapshot> skuMap,
                                                 LocalDateTime now,
                                                 Map<Long, Integer> stockMap) {
        SeckillActivityListItemVO vo = new SeckillActivityListItemVO();
        fillItemFields(vo, activity, skuMap.get(activity.getSkuId()), now,
                stockMap.getOrDefault(activity.getId(), activity.getAvailableStock()));
        return vo;
    }

    private void fillItemFields(SeckillActivityListItemVO vo,
                                SeckillActivity activity,
                                SkuSnapshot sku,
                                LocalDateTime now,
                                int availableStock) {
        vo.setId(activity.getId());
        vo.setSpuId(activity.getSpuId());
        vo.setSkuId(activity.getSkuId());
        vo.setName(activity.getName());
        vo.setProductName(sku != null && sku.spuName() != null ? sku.spuName() : activity.getName());
        vo.setImage(sku != null && sku.image() != null
                ? sku.image()
                : (sku == null ? null : sku.spuMainImage()));
        vo.setSpecDesc(sku != null ? sku.specDesc() : null);
        vo.setSeckillPrice(activity.getSeckillPrice());
        vo.setOriginalPrice(sku != null ? sku.price() : null);
        vo.setTotalStock(activity.getTotalStock());
        vo.setAvailableStock(availableStock);
        vo.setStartTime(activity.getStartTime());
        vo.setEndTime(activity.getEndTime());
        vo.setPhase(phaseOf(activity.getStartTime(), activity.getEndTime(), availableStock, now));
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
