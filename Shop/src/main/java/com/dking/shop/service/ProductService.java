package com.dking.shop.service;

import com.dking.shop.vo.PageVO;
import com.dking.shop.vo.ProductDetailVO;
import com.dking.shop.vo.ProductListItemVO;

public interface ProductService {

    /**
     * 商品列表：仅上架商品，按销量降序分页。
     *
     * @param page       页码，从 1 开始
     * @param size       每页条数，最大 50
     * @param categoryId 分类 id（一级或二级），为空表示全部
     */
    PageVO<ProductListItemVO> list(int page, int size, Long categoryId);

    /**
     * 商品详情：SPU + 图片 + 规格 + 上架 SKU（含可用库存）。
     * 目前一次详情页，查6次数据库
     * @throws com.dking.shop.exception.NotFoundException 商品不存在或非上架状态
     */
    ProductDetailVO detail(Long spuId);
}
