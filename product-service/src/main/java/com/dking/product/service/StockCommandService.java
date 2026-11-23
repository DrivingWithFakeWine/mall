package com.dking.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.product.dto.StockItem;
import com.dking.product.dto.StockOpRequest;
import com.dking.product.dto.StockOpResult;
import com.dking.product.entity.ProductSku;
import com.dking.product.entity.ProductStock;
import com.dking.product.entity.StockOpLog;
import com.dking.product.exception.StockErrorCode;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存写操作：预占 / 释放 / 确认 / 销量。
 *
 * <h3>核心约束</h3>
 *
 * <p><b>1. 有状态机，不是四个独立接口。</b>一个 (orderNo, skuId) 的生命周期是：</p>
 * <pre>
 *             RESERVE                  RELEASE
 *   (无记录) ─────────▶ RESERVED ──────────────▶ RELEASED
 *                          │
 *                          └── CONFIRM ────────▶ CONFIRMED
 * </pre>
 * <p>释放/确认都必须能找到对应的预占记录，且数量一致、没被反向操作过。
 * 只靠 {@code locked_stock >= n} 判断是错的——locked 是整个 SKU 共享的计数器，
 * 不校验归属的话，一笔没预占过的订单可以"释放"掉别人锁定的库存。</p>
 *
 * <p><b>2. 幂等靠台账。</b>唯一键 (orderNo, skuId, op) 保证重复调用只生效一次；
 * 重试命中已有记录直接跳过并返回成功。</p>
 *
 * <p><b>3. 一个请求一个本地事务。</b>多明细要么全成功要么全回滚，包括台账。</p>
 *
 * <p><b>4. 重复 skuId 先合并。</b>否则第二条会被幂等键静默丢弃。</p>
 *
 * <p><b>5. 台账的 before/after 只是排查线索</b>：由「操作前一次批量读 + 按 op 推算」得到，
 * 并发下是近似值，不能用于对账。</p>
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

    /** 预占：available -n，locked +n */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult reserve(StockOpRequest request) {
        return apply(request, OP_RESERVE);
    }

    /** 释放：available +n，locked -n。必须存在预占记录 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult release(StockOpRequest request) {
        return apply(request, OP_RELEASE);
    }

    /** 确认（支付后）：locked -n。必须存在预占记录且未释放 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult confirm(StockOpRequest request) {
        return apply(request, OP_CONFIRM);
    }

    /** 销量累加：SKU + SPU。秒杀单支付也走这里 */
    @Transactional(rollbackFor = Exception.class)
    public StockOpResult sales(StockOpRequest request) {
        List<StockItem> items = validateAndNormalize(request);
        Map<Long, Map<String, Integer>> ledger = loadLedger(request.orderNo(), items);

        Map<Long, Long> skuToSpu = skuMapper.selectBatchIds(
                        items.stream().map(StockItem::skuId).toList())
                .stream()
                .collect(Collectors.toMap(ProductSku::getId, ProductSku::getSpuId));

        int applied = 0;
        int skipped = 0;
        for (StockItem item : items) {
            Long spuId = skuToSpu.get(item.skuId());
            if (spuId == null) {
                throw new StockOpException(StockErrorCode.SKU_NOT_FOUND, "SKU 不存在: " + item.skuId());
            }
            Integer recorded = ledger.getOrDefault(item.skuId(), Map.of()).get(OP_SALES);
            if (recorded != null) {
                requireSameQuantity(recorded, item);
                skipped++;
                continue;
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

    private StockOpResult apply(StockOpRequest request, String op) {
        List<StockItem> items = validateAndNormalize(request);
        Map<Long, Map<String, Integer>> ledger = loadLedger(request.orderNo(), items);
        Map<Long, ProductStock> stocks = loadStocks(items);

        int applied = 0;
        int skipped = 0;
        for (StockItem item : items) {
            Map<String, Integer> state = ledger.getOrDefault(item.skuId(), Map.of());
            if (decide(op, item, state) == Decision.SKIP) {
                skipped++;
                continue;
            }

            if (!mark(buildRecord(request.orderNo(), item, op, stocks.get(item.skuId())))) {
                // 并发下被另一个线程抢先写入台账 → 本次视为重复
                skipped++;
                continue;
            }

            int rows = updateStock(op, item);
            if (rows == 0) {
                throw new StockOpException(switch (op) {
                    case OP_RESERVE -> StockErrorCode.INSUFFICIENT_STOCK;
                    case OP_RELEASE -> StockErrorCode.ALREADY_RELEASED;
                    default -> StockErrorCode.ALREADY_CONFIRMED;
                }, switch (op) {
                    case OP_RESERVE -> "库存不足: skuId=" + item.skuId();
                    case OP_RELEASE -> "锁定库存不足，无法释放: skuId=" + item.skuId();
                    default -> "锁定库存不足，无法确认: skuId=" + item.skuId();
                });
            }
            applied++;
            ledger.computeIfAbsent(item.skuId(), key -> new HashMap<>()).put(op, item.quantity());
        }

        log.info("库存操作完成 op={}, orderNo={}, applied={}, skipped={}", op, request.orderNo(), applied, skipped);
        return StockOpResult.ok(op, request.orderNo(), applied, skipped);
    }

    private enum Decision {APPLY, SKIP}

    /**
     * 状态机校验：这一步决定「执行」还是「幂等跳过」，非法状态直接抛异常。
     *
     * <p>这是防止「没预占就释放」偷走别人锁定的关键。</p>
     */
    private Decision decide(String op, StockItem item, Map<String, Integer> state) {
        Integer reserved = state.get(OP_RESERVE);
        Integer released = state.get(OP_RELEASE);
        Integer confirmed = state.get(OP_CONFIRM);

        switch (op) {
            case OP_RESERVE -> {
                if (reserved != null) {
                    requireSameQuantity(reserved, item);
                    return Decision.SKIP;
                }
                // 防御手段，告警后，交给对账任务.
                if (released != null || confirmed != null) {
                    throw new StockOpException(StockErrorCode.INVALID_REQUEST,
                            "台账状态异常：存在释放/确认记录但没有预占: skuId=" + item.skuId());
                }
                return Decision.APPLY;
            }
            case OP_RELEASE -> {
                if (released != null) {
                    requireSameQuantity(released, item);
                    return Decision.SKIP;
                }
                if (confirmed != null) {
                    throw new StockOpException(StockErrorCode.ALREADY_CONFIRMED,
                            "已确认的订单不能释放: skuId=" + item.skuId());
                }
                if (reserved == null) {
                    throw new StockOpException(StockErrorCode.NOT_RESERVED,
                            "该订单未预占库存，不能释放: skuId=" + item.skuId());
                }
                requireSameQuantity(reserved, item);
                return Decision.APPLY;
            }
            case OP_CONFIRM -> {
                if (confirmed != null) {
                    requireSameQuantity(confirmed, item);
                    return Decision.SKIP;
                }
                if (released != null) {
                    throw new StockOpException(StockErrorCode.ALREADY_RELEASED,
                            "已释放的订单不能确认: skuId=" + item.skuId());
                }
                if (reserved == null) {
                    throw new StockOpException(StockErrorCode.NOT_RESERVED,
                            "该订单未预占库存，不能确认: skuId=" + item.skuId());
                }
                requireSameQuantity(reserved, item);
                return Decision.APPLY;
            }
            default -> throw new IllegalStateException("未知库存操作: " + op);
        }
    }

    private int updateStock(String op, StockItem item) {
        return switch (op) {
            case OP_RESERVE -> stockMapper.reserve(item.skuId(), item.quantity());
            case OP_RELEASE -> stockMapper.release(item.skuId(), item.quantity());
            case OP_CONFIRM -> stockMapper.confirm(item.skuId(), item.quantity());
            default -> throw new IllegalStateException("未知库存操作: " + op);
        };
    }

    /** 一次批量读台账，构建 orderNo 下每个 SKU 已执行过哪些操作 */
    private Map<Long, Map<String, Integer>> loadLedger(String orderNo, List<StockItem> items) {
        List<Long> skuIds = items.stream().map(StockItem::skuId).toList();
        List<StockOpLog> rows = logMapper.selectList(new LambdaQueryWrapper<StockOpLog>()
                .eq(StockOpLog::getOrderNo, orderNo)
                .in(StockOpLog::getSkuId, skuIds));
        Map<Long, Map<String, Integer>> result = new HashMap<>();
        for (StockOpLog row : rows) {
            result.computeIfAbsent(row.getSkuId(), key -> new HashMap<>())
                    .put(row.getOp(), row.getQuantity());
        }
        return result;
    }

    /** 一次批量读库存，给台账的 before 快照用；读不到的 SKU 快照留空 */
    private Map<Long, ProductStock> loadStocks(List<StockItem> items) {
        List<Long> skuIds = items.stream().map(StockItem::skuId).toList();
        return stockMapper.selectList(new LambdaQueryWrapper<ProductStock>()
                        .in(ProductStock::getSkuId, skuIds)
                        .select(ProductStock::getSkuId,
                                ProductStock::getAvailableStock,
                                ProductStock::getLockedStock))
                .stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, identity -> identity));
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
            int available = stock.getAvailableStock();  // 查商品可用库存
            int locked = stock.getLockedStock();        // 查商品锁住的库存
            int quantity = item.quantity();         //
            record.setAvailableBefore(available);       // 修改前可用的库存
            record.setLockedBefore(locked);         // 修改前锁住的库存
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
     * 写台账；并发下被别的线程抢先时返回 false（本次跳过）。
     */
    private boolean mark(StockOpLog record) {
        return logMapper.insertIfAbsent(record) == 1;
    }

    private void requireSameQuantity(int recorded, StockItem item) {
        if (recorded != item.quantity()) {
            throw new StockOpException(StockErrorCode.QUANTITY_MISMATCH,
                    "同一订单同一 SKU 的数量不一致: skuId=" + item.skuId()
                            + "，已记录=" + recorded + "，本次=" + item.quantity());
        }
    }

    /**
     * 校验并把重复 skuId 合并成一条。
     *
     * <p>不合并的话，第二条会因为台账唯一键命中而被跳过，数量被静默丢掉。</p>
     */
    private List<StockItem> validateAndNormalize(StockOpRequest request) {
        if (request == null) {
            throw new StockOpException(StockErrorCode.INVALID_REQUEST, "请求为空");
        }
        if (request.orderNo() == null || request.orderNo().isBlank()) {
            throw new StockOpException(StockErrorCode.INVALID_REQUEST, "orderNo 不能为空");
        }
        if (request.orderNo().length() > 32) {
            throw new StockOpException(StockErrorCode.INVALID_REQUEST, "orderNo 超过 32 字符");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new StockOpException(StockErrorCode.INVALID_REQUEST, "items 不能为空");
        }
        if (request.items().size() > MAX_ITEMS) {
            throw new StockOpException(StockErrorCode.INVALID_REQUEST, "items 超过上限 " + MAX_ITEMS);
        }

        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (StockItem item : request.items()) {
            if (item == null || item.skuId() == null) {
                throw new StockOpException(StockErrorCode.INVALID_REQUEST, "skuId 不能为空");
            }
            if (item.quantity() == null || item.quantity() <= 0 || item.quantity() > MAX_QUANTITY) {
                throw new StockOpException(StockErrorCode.INVALID_REQUEST,
                        "quantity 必须在 1~" + MAX_QUANTITY + " 之间: skuId=" + item.skuId());
            }
            int total = merged.merge(item.skuId(), item.quantity(), Integer::sum);
            if (total > MAX_QUANTITY) {
                throw new StockOpException(StockErrorCode.INVALID_REQUEST,
                        "同一 SKU 合计数量超过 " + MAX_QUANTITY + ": skuId=" + item.skuId());
            }
        }
        return merged.entrySet().stream()
                .map(entry -> new StockItem(entry.getKey(), entry.getValue()))
                .toList();
    }
}
