package com.dking.shop.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SeckillStockBucketMapper {

    /**
     * 幂等初始化：一条语句写入全部桶，避免并发初始化出现混合分布。
     * 总量按 total_stock 均分（余数给前几个桶），当前可售按 available_stock 均分。
     */
    @Insert("""
            INSERT IGNORE INTO seckill_stock_bucket (activity_id, bucket_no, total_stock, available_stock)
            SELECT #{activityId}, seq.n,
                   (#{totalStock} DIV #{buckets}) + IF(seq.n < (#{totalStock} MOD #{buckets}), 1, 0),
                   (#{availableStock} DIV #{buckets}) + IF(seq.n < (#{availableStock} MOD #{buckets}), 1, 0)
            FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
                  UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
                  UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
                  UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15) seq
            WHERE seq.n < #{buckets}
            """)
    int initialize(@Param("activityId") Long activityId,
                   @Param("totalStock") int totalStock,
                   @Param("availableStock") int availableStock,
                   @Param("buckets") int buckets);

    @Select("SELECT COUNT(*) FROM seckill_stock_bucket WHERE activity_id = #{activityId}")
    int countByActivity(@Param("activityId") Long activityId);

    /**
     * 扣减一个桶。JOIN 活动行做状态/时间校验但不更新它，避免把热点重新拉回单行。
     */
    @Update("""
            UPDATE seckill_stock_bucket b
            JOIN seckill_activity a ON a.id = b.activity_id
            SET b.available_stock = b.available_stock - 1
            WHERE b.activity_id = #{activityId}
              AND b.bucket_no = #{bucketNo}
              AND b.available_stock > 0
              AND a.status = 1
              AND a.start_time <= NOW()
              AND a.end_time > NOW()
            """)
    int deduct(@Param("activityId") Long activityId, @Param("bucketNo") int bucketNo);

    /** 回补，不允许超过该桶总量 */
    @Update("""
            UPDATE seckill_stock_bucket
            SET available_stock = available_stock + #{quantity}
            WHERE activity_id = #{activityId}
              AND bucket_no = #{bucketNo}
              AND available_stock + #{quantity} <= total_stock
            """)
    int restore(@Param("activityId") Long activityId,
                @Param("bucketNo") int bucketNo,
                @Param("quantity") int quantity);

    @Select("SELECT COALESCE(SUM(available_stock), 0) FROM seckill_stock_bucket WHERE activity_id = #{activityId}")
    Integer totalAvailable(@Param("activityId") Long activityId);

    @Select("""
            <script>
            SELECT activity_id AS activityId, COALESCE(SUM(available_stock), 0) AS total
            FROM seckill_stock_bucket
            WHERE activity_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY activity_id
            </script>
            """)
    List<Map<String, Object>> sumByActivityIds(@Param("ids") List<Long> ids);

    /** 把桶汇总写回活动行，仅作为展示缓存；不在下单路径调用 */
    @Update("""
            UPDATE seckill_activity
            SET available_stock = (SELECT COALESCE(SUM(available_stock), 0)
                                   FROM seckill_stock_bucket
                                   WHERE activity_id = #{activityId})
            WHERE id = #{activityId}
            """)
    int refreshActivityStock(@Param("activityId") Long activityId);
}
