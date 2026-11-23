package com.dking.shop.client;

import com.dking.shop.client.dto.StockErrorResponse;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把商品服务返回的 409 翻译成 {@link StockOpRejectedException}。
 *
 * <p>没有它的话，Feign 会抛一个只有状态码的 {@code FeignException}，
 * 上游只能靠字符串匹配判断是不是「库存不足」。</p>
 */
@Component
public class ProductClientErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(ProductClientErrorDecoder.class);

    private final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Exception decode(String methodKey, Response response) {
        if (response.status() == 409 && response.body() != null) {
            try {
                byte[] body = Util.toByteArray(response.body().asInputStream());
                StockErrorResponse error = json.readValue(body, StockErrorResponse.class);
                log.warn("商品服务拒绝, method={}, code={}, message={}",
                        methodKey, error.code(), error.message());
                return new StockOpRejectedException(error.code(), error.message());
            } catch (Exception e) {
                log.warn("解析商品服务错误响应失败, method={}, err={}", methodKey, e.toString());
            }
        }
        return defaultDecoder.decode(methodKey, response);
    }
}
