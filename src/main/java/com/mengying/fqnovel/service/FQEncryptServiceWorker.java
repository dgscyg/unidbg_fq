package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.UnidbgProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Objects;

@Service("fqEncryptWorker")
public class FQEncryptServiceWorker {

    private static final Logger log = LoggerFactory.getLogger(FQEncryptServiceWorker.class);

    private final FQEncryptService signer;

    @Autowired
    public FQEncryptServiceWorker(UnidbgProperties unidbgProperties) {
        UnidbgProperties properties = Objects.requireNonNull(unidbgProperties, "unidbgProperties must not be null");
        this.signer = new FQEncryptService(properties);
    }

    /**
     * 同步生成FQ签名headers (重载方法，支持Map格式的headers)
     *
     * @param url 请求的URL
     * @param headerMap 请求头的Map
     * @return 包含签名信息的签名头
     */
    public synchronized Map<String, String> generateSignatureHeadersSync(String url, Map<String, String> headerMap) {
        return signer.generateSignatureHeaders(url, headerMap);
    }

    @PreDestroy
    public void destroy() {
        try {
            signer.destroy();
        } catch (Exception e) {
            log.warn("销毁签名服务时发生异常", e);
        }
    }
}
