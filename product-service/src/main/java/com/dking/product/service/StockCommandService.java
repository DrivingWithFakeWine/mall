package com.dking.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.product.dto.StockItem;
import com.dking.product.dto.StockOpRequest;
import com.dking.product.dto.StockOpResult;
import com.dking.product.entity.ProductSku;
import com.dking.product.entity.StockOpLog;
import com.dking.product.exception.StockOpException;
import com.dking.product.mapper.ProductSkuMapper;
import com.dking.product.mapper.ProductSpuMapper;
import com.dking.product.mapper.ProductStockMapper;
import com.dking.product.mapper.StockOpLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 库存写操作：预占 / 释放 / 确认 / 销量。
 *
 * <h3>为什么这样设计</h3>
 *
 * <p><b>1. 幂等靠台账，不靠调用方自觉。</b>跨进程调用一定会重试（超时、网络抖动、上游重试策略），
 * 每次操作先写一条 {@code stock_op_log}，唯一键 (orderNo, skuId, op) 保证同一操作只生效一次。
 * 重试时命中已有记录，直接跳过并返回成功。</p>
 *
 * <p><b>2. 整个请求一个本地事务。</b>一个订单的多条明细要么全预占成功，要么全回滚。
 * 中途某条库存不足会抛异常，台账和已扣的库存一起回滚——不会留下"扣了一半"的状态。</p>
 *
 * <p><b>3. 数量不一致要报错，不能悄悄跳过。</b>同一 orderNo + skuId 重试时数量变了，
 * 说明上游有 bug（比如重算了一次金额），必须暴露而不是放过。</p>
 *
 * <p><b>4. 失败模式是"少卖"不是"超卖"。</b>预占失败只会导致订单创建失败；
 * 预占成功但订单没建成，由对账任务补偿释放。</p>
 */
@Service
public class StockCommandService {

    private static final Logger log = LoggerFactory.getLogger(StockCommandService.class);

    private static final int MAX_ITEMS = 100;
    private static final int MAX_QUANTITY = 999;

    private static final String OP_RESERVE = "RESERVE";
    private static final String OP_RELEASE = "RELEASE";
    private static final String OP_CONFIRM = "CONFIRM";
    private static final String OP_SALES = "SALES";

    private final ProductStockMapper stockMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductSpuMapper spuMapper;
    private final StockOpLogMapper logMapper;

    public StockCommandService(ProductStockMapper stockMapper,
                               ProductSkuMapper skuMapper,
                               ProductSpuMapper spuMapper,
                               StockOpLogMapper logMapper) {
        this.stockMapper = stockMapper;
        this.skuMapper = skuMapper;
        this.spuMapper = spuMapper;
        this.logMapper = logMapper;
    }

    /**
     * 预占库存：available -n，locked +n。
     * 库存不足直接拒绝，调用方据此返回"库存不足"而不是先建单。
     */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult reserve(StockOpRequest request) {
        validate(request);
        int applied = 0;
        int skipped = 0;
        for (StockItem item : request.items()) {
            if (!mark(request.orderNo(), item.skuId(), OP_RESERVE, item.quantity())) {
                skipped++;
                continue;
            }
            if (stockMapper.reserve(item.skuId(), item.quantity()) == 0) {
                throw new StockOpException("库存不足: skuId=" + item.skuId());
            }
            applied++;
        }
        log.info("库存预占 orderNo={}, applied={}, skipped={}", request.orderNo(), applied, skipped);
        return StockOpResult.ok(OP_RESERVE, request.orderNo(), applied, skipped);
    }

    /** 释放预占：available +n，locked -n。取消、超时、下单失败补偿都用它。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult release(StockOpRequest request) {
        validate(request);
        int applied = 0;
        int skipped = 0;
        for (StockItem item : request.items()) {
            if (!mark(request.orderNo(), item.skuId(), OP_RELEASE, item.quantity())) {
                skipped++;
                continue;
            }
            if (stockMapper.release(item.skuId(), item.quantity()) == 0) {
                throw new StockOpException("锁定库存不足，无法释放: skuId=" + item.skuId());
            }
            applied++;
        }
        log.info("库存释放 orderNo={}, applied={}, skipped={}", request.orderNo(), applied, skipped);
        return StockOpResult.ok(OP_RELEASE, request.orderNo(), applied, skipped);
    }

    /** 确认（支付后）：locked -n，可用库存不变。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult confirm(StockOpRequest request) {
        validate(request);
        int applied = 0;
        int skipped = 0;
        for (StockItem item : request.items()) {
            if (!mark(request.orderNo(), item.skuId(), OP_CONFIRM, item.quantity())) {
                skipped++;
                continue;
            }
            if (stockMapper.confirm(item.skuId(), item.quantity()) == 0) {
                throw new StockOpException("锁定库存不足，无法确认: skuId=" + item.skuId());
            }
            applied++;
        }
        log.info("库存确认 orderNo={}, applied={}, skipped={}", request.orderNo(), applied, skipped);
        return StockOpResult.ok(OP_CONFIRM, request.orderNo(), applied, skipped);
    }

    /** 销量累加：SKU 和 SPU 各加一次。秒杀单支付也走这里（秒杀库存不在这张表）。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult sales(StockOpRequest request) {
        validate(request);

        List<Long> skuIds = request.items().stream().map(StockItem::skuId).distinct().toList();
        Map<Long, Long> skuToSpu = skuMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(ProductSku::getId, ProductSku::getSpuId));

        int applied = 0;
        int skipped = 0;
        for (StockItem item : request.items()) {
            Long spuId = skuToSpu.get(item.skuId());
            if (spuId == null) {
                throw new StockOpException("SKU 不存在: " + item.skuId());
            }
            if (!mark(request.orderNo(), item.skuId(), OP_SALES, item.quantity())) {
                skipped++;
                continue;
            }
            skuMapper.addSales(item.skuId(), item.quantity());
            spuMapper.addSales(spuId, item.quantity());
            applied++;
        }
        log.info("销量累加 orderNo={}, applied={}, skipped={}", request.orderNo(), applied, skipped);
        return StockOpResult.ok(OP_SALES, request.orderNo(), applied, skipped);
    }

    /**
     * 写台账。
     *
     * @return true = 首次执行，需要做库存变更；false = 已执行过，跳过
     * @throws StockOpException 同一 orderNo + skuId 的数量与已记录的不一致
     */
    private boolean mark(String orderNo, Long skuId, String op, int quantity) {
        StockOpLog existing = logMapper.selectOne(new LambdaQueryWrapper<StockOpLog>()
                .eq(StockOpLog::getOrderNo, orderNo)
                .eq(StockOpLog::getSkuId, skuId)
                .eq(StockOpLog::getOp, op)
                .last("LIMIT 1"));
        if (existing != null) {
            if (!Objects.equals(existing.getQuantity(), quantity)) {
                throw new StockOpException("同一订单同一 SKU 的数量不一致: skuId=" + skuId
                        + "，已记录=" + existing.getQuantity() + "，本次=" + quantity);
            }
            return false;
        }

        StockOpLog record = new StockOpLog();
        record.setId(IdWorker.getId());
        record.setOrderNo(orderNo);
        record.setSkuId(skuId);
        record.setOp(op);
        record.setQuantity(quantity);
        record.setCreateTime(LocalDateTime.now());
        return logMapper.insertIfAbsent(record) == 1;
    }

    private void validate(StockOpRequest request) {
        if (request == null) {
            throw new StockOpException("请求为空");
        }
        if (request.orderNo() == null || request.orderNo().isBlank()) {
            throw new StockOpException("orderNo 不能为空");
        }
        if (request.orderNo().length() > 32) {
            throw new StockOpException("orderNo 超过 32 字符");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new StockOpException("items 不能为空");
        }
        if (request.items().size() > MAX_ITEMS) {
            throw new StockOpException("items 超过上限 " + MAX_ITEMS);
        }
        for (StockItem item : request.items()) {
            if (item == null || item.skuId() == null) {
                throw new StockOpException("skuId 不能为空");
            }
            if (item.quantity() == null || item.quantity() <= 0 || item.quantity() > MAX_QUANTITY) {
                throw new StockOpException("quantity 必须在 1~" + MAX_QUANTITY + " 之间: skuId=" + item.skuId());
            }
        }
    }
}
