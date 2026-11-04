package com.dking.shop.util;

/**
 * 关键字搜索：去空白、限长，并转义 LIKE 通配符，防止 "%" 或 "_" 退化成全表匹配。
 */
public final class LikeUtils {

    private static final int MAX_KEYWORD_LENGTH = 50;

    private LikeUtils() {
    }

    /** 返回可直接传给 MP like() 的关键字；空白返回 null 表示不筛选 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String keyword = raw.trim();
        if (keyword.isEmpty()) {
            return null;
        }
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
        }
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
