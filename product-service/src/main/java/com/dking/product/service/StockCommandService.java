package com.dking.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.product.dto.StockItem;
import com.dking.product.dto.StockOpRequest;
import com.dking.product.dto.StockOpResult;
import com.dking.product.entity.ProductSku;
import com.dking.product.entity.ProductStock;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
 * <p><b>4. 重复 skuId 先合并。</b>同一个请求里出现两次相同 SKU，合并成一条；
 * 否则第二条会因为台账唯一键被跳过，数量被静默丢掉。</p>
 *
 * <p><b>5. 失败模式是"少卖"不是"超卖"。</b>预占失败只会导致订单创建失败；
 * 预占成功但订单没建成，由对账任务补偿释放。</p>
 *
 * <p><b>关于台账里的 before/after：</b>由「操作前批量读一次库存 + 按 op 推算」得到，
 * 并发下是近似值，只作为排查线索。</p>
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
        return apply(request, OP_RESERVE);
    }

    /** 释放预占：available +n，locked -n。取消、超时、下单失败补偿都用它。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult release(StockOpRequest request) {
        return apply(request, OP_RELEASE);
    }

    /** 确认（支付后）：locked -n，可用库存不变。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult confirm(StockOpRequest request) {
        return apply(request, OP_CONFIRM);
    }

    /** 销量累加：SKU 和 SPU 各加一次。秒杀单支付也走这里（秒杀库存不在这张表）。 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult sales(StockOpRequest request) {
        List<StockItem> items = validateAndNormalize(request);

        Map<Long, Long> skuToSpu = skuMapper.selectBatchIds(
                        items.stream().map(StockItem::skuId).toList())
                .stream()
                .collect(Collectors.toMap(ProductSku::getId, ProductSku::getSpuId));

        int applied = 0;
        int skipped = 0;
        for (StockItem item : items) {
            Long spuId = skuToSpu.get(item.skuId());
            if (spuId == null) {
                throw new StockOpException("SKU 不存在: " + item.skuId());
            }
            // 销量不动库存，快照列留空
            if (!mark(buildRecord(request.orderNo(), item, OP_SALES, null))) {
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

    /** 预占/释放/确认的公共流程 */
    private StockOpResult apply(StockOpRequest request, String op) {
        List<StockItem> items = validateAndNormalize(request);
        Map<Long, ProductStock> beforeSnapshot = loadStocks(items);

        int applied = 0;
        int skipped = 0;
        for (StockItem item : items) {
            StockOpLog record = buildRecord(request.orderNo(), item, op, beforeSnapshot.get(item.skuId()));
            if (!mark(record)) {
                skipped++;
                continue;
            }
            int rows = switch (op) {
                case OP_RESERVE -> stockMapper.reserve(item.skuId(), item.quantity());
                case OP_RELEASE -> stockMapper.release(item.skuId(), item.quantity());
                case OP_CONFIRM -> stockMapper.confirm(item.skuId(), item.quantity());
                default -> throw new IllegalStateException("未知库存操作: " + op);
            };
            if (rows == 0) {
                throw new StockOpException(switch (op) {
                    case OP_RESERVE -> "库存不足: skuId=" + item.skuId();
                    case OP_RELEASE -> "锁定库存不足，无法释放: skuId=" + item.skuId();
                    default -> "锁定库存不足，无法确认: skuId=" + item.skuId();
                });
            }
            applied++;
        }
        log.info("库存操作完成 op={}, orderNo={}, applied={}, skipped={}", op, request.orderNo(), applied, skipped);
        return StockOpResult.ok(op, request.orderNo(), applied, skipped);
    }

    /** 一次批量读，给台账的 before 快照用；读不到的 SKU 快照留空 */
    private Map<Long, ProductStock> loadStocks(List<StockItem> items) {
        List<Long> skuIds = items.stream().map(StockItem::skuId).toList();
        return stockMapper.selectList(new LambdaQueryWrapper<ProductStock>()
                        .in(ProductStock::getSkuId, skuIds)
                        .select(ProductStock::getSkuId,
                                ProductStock::getAvailableStock,
                                ProductStock::getLockedStock))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));
    }

    private StockOpLog buildRecord(String orderNo, StockItem item, String op, ProductStock stock) {
        StockOpLog record = new StockOpLog();
        record.setId(IdWorker.getId());
        record.setOrderNo(orderNo);
        record.setSkuId(item.skuId());
        record.setOp(op);
        record.setQuantity(item.quantity());
        record.setCreateTime(LocalDateTime.now());

        if (stock != null && !OP_SALES.equals(op)) {
            int available = stock.getAvailableStock();
            int locked = stock.getLockedStock();
            int quantity = item.quantity();
            record.setAvailableBefore(available);
            record.setLockedBefore(locked);
            switch (op) {
                case OP_RESERVE -> {
                    record.setAvailableAfter(available - quantity);
                    record.setLockedAfter(locked + quantity);
                }
                case OP_RELEASE -> {
                    record.setAvailableAfter(available + quantity);
                    record.setLockedAfter(locked - quantity);
                }
                case OP_CONFIRM -> {
                    record.setAvailableAfter(available);
                    record.setLockedAfter(locked - quantity);
                }
                default -> {
                    // 不会到这里
                }
            }
        }
        return record;
    }

    /**
     * 写台账。
     *
     * @return true = 首次执行，需要做库存变更；false = 已执行过，跳过
     * @throws StockOpException 同一 orderNo + skuId 的数量与已记录的不一致
     */
    private boolean mark(StockOpLog record) {
        StockOpLog existing = logMapper.selectOne(new LambdaQueryWrapper<StockOpLog>()
                .eq(StockOpLog::getOrderNo, record.getOrderNo())
                .eq(StockOpLog::getSkuId, record.getSkuId())
                .eq(StockOpLog::getOp, record.getOp())
                .last("LIMIT 1"));
        if (existing != null) {
            if (!Objects.equals(existing.getQuantity(), record.getQuantity())) {
                throw new StockOpException("同一订单同一 SKU 的数量不一致: skuId=" + record.getSkuId()
                        + "，已记录=" + existing.getQuantity() + "，本次=" + record.getQuantity());
            }
            return false;
        }
        return logMapper.insertIfAbsent(record) == 1;
    }

    /**
     * 校验并把重复 skuId 合并成一条。
     *
     * <p>不合并的话，第二条会因为台账唯一键命中而被跳过，数量被静默丢掉。</p>
     */
    private List<StockItem> validateAndNormalize(StockOpRequest request) {
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

        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (StockItem item : request.items()) {
            if (item == null || item.skuId() == null) {
                throw new StockOpException("skuId 不能为空");
            }
            if (item.quantity() == null || item.quantity() <= 0 || item.quantity() > MAX_QUANTITY) {
                throw new StockOpException("quantity 必须在 1~" + MAX_QUANTITY + " 之间: skuId=" + item.skuId());
            }
            int total = merged.merge(item.skuId(), item.quantity(), Integer::sum);
            if (total > MAX_QUANTITY) {
                throw new StockOpException("同一 SKU 合计数量超过 " + MAX_QUANTITY + ": skuId=" + item.skuId());
            }
        }
        return merged.entrySet().stream()
                .map(entry -> new StockItem(entry.getKey(), entry.getValue()))
                .toList();
    }
}
