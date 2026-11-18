package com.dking.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dking.product.entity.StockOpLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface StockOpLogMapper extends BaseMapper<StockOpLog> {

    /**
     * 插入台账；已存在（唯一键冲突）时不报错，返回 0。
     *
     * <p>用 ON DUPLICATE KEY 而不是先查后插：并发重试时不会抛 DuplicateKeyException
     * 把事务标记成 rollback-only。</p>
     *
     * @return 1 = 本次插入（需要执行库存变更）；0 = 已存在（跳过）
     */
    @Insert("""
            INSERT INTO stock_op_log (id, order_no, sku_id, op, quantity, create_time)
            VALUES (#{log.id}, #{log.orderNo}, #{log.skuId}, #{log.op}, #{log.quantity}, #{log.createTime})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(@Param("log") StockOpLog log);
}
