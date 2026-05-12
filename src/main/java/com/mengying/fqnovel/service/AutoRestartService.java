package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class AutoRestartService {

    private static final Logger log = LoggerFactory.getLogger(AutoRestartService.class);
    private static final String REASON_PREFIX_AUTO_SELF_HEAL = "AUTO_SELF_HEAL:";

    private final FQDownloadProperties downloadProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;
    private final ScheduledExecutorService restartExecutor =
        Executors.newScheduledThreadPool(2, new NamedDaemonThreadFactory("auto-restart-"));

    public AutoRestartService(
        FQDownloadProperties downloadProperties,
        FQDeviceRotationService deviceRotationService,
        FQRegisterKeyService registerKeyService
    ) {
        this.downloadProperties = downloadProperties;
        this.deviceRotationService = deviceRotationService;
        this.registerKeyService = registerKeyService;
    }

    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicInteger upstreamEmptyErrorCount = new AtomicInteger(0);
    private final AtomicBoolean healing = new AtomicBoolean(false);
    private volatile long windowStartMs = 0L;
    private volatile long upstreamEmptyWindowStartMs = 0L;
    private volatile long lastRestartAtMs = 0L;
    private volatile long lastSelfHealAtMs = 0L;

    public synchronized void recordSuccess() {
        errorCount.set(0);
        upstreamEmptyErrorCount.set(0);
        windowStartMs = 0L;
        upstreamEmptyWindowStartMs = 0L;
    }

    public void recordFailure(String reason) {
        if (!downloadProperties.getAutoRestart().isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean upstreamEmpty = isUpstreamEmptyReason(reason);
        int threshold = autoRestartThreshold(upstreamEmpty);

        int count;
        synchronized (this) {
            long windowMs = autoRestartWindowMs();
            AtomicInteger counter = upstreamEmpty ? upstreamEmptyErrorCount : errorCount;
            long start = upstreamEmpty ? upstreamEmptyWindowStartMs : windowStartMs;
            if (start == 0L || now - start > windowMs) {
                if (upstreamEmpty) {
                    upstreamEmptyWindowStartMs = now;
                } else {
                    windowStartMs = now;
                }
                counter.set(0);
            }
            count = counter.incrementAndGet();
        }

        if (count < threshold) {
            return;
        }

        if (trySelfHeal(reason, now, count, threshold)) {
            return;
        }

        long minIntervalMs = autoRestartMinIntervalMs();
        if (now - lastRestartAtMs < minIntervalMs) {
            return;
        }
        lastRestartAtMs = now;

        log.error("连续异常达到阈值，但已禁用自动退出重启: count={}, threshold={}, reason={}", count, threshold, reason);
    }

    private boolean trySelfHeal(String reason, long now, int count, int threshold) {
        if (!downloadProperties.getAutoRestart().isSelfHealEnabled()) {
            return false;
        }

        if (healing.get()) {
            return true;
        }

        long cooldownMs = selfHealCooldownMs();
        if (cooldownMs > 0 && now - lastSelfHealAtMs < cooldownMs) {
            return false;
        }
        if (!healing.compareAndSet(false, true)) {
            return true;
        }
        lastSelfHealAtMs = now;

        log.warn("连续异常达到阈值，优先尝试自愈（重置签名服务 / 切换设备）: count={}, threshold={}, reason={}", count, threshold, reason);
        String selfHealReason = prefixedReason(REASON_PREFIX_AUTO_SELF_HEAL, reason);

        // 自愈逻辑放后台线程，避免阻塞当前业务线程；失败后仅保留计数并继续运行，不再退出进程。
        restartExecutor.execute(() -> {
            boolean resetOk = false;
            boolean invalidateKeyOk = false;
            boolean rotateOk = false;
            try {
                resetOk = runSelfHealStep("请求重置签名服务失败", () -> {
                    FQEncryptServiceWorker.requestGlobalReset(selfHealReason);
                    return true;
                });
                invalidateKeyOk = runSelfHealStep("失效当前 registerkey 失败", () -> {
                    registerKeyService.invalidateCurrentKey();
                    return true;
                });
                rotateOk = runSelfHealStep("切换设备失败", () ->
                    deviceRotationService.forceRotate(selfHealReason)
                );
            } finally {
                healing.set(false);
                if (resetOk && invalidateKeyOk) {
                    recordSuccess();
                    if (!rotateOk) {
                        log.warn("自愈未完成设备切换，但签名服务/registerkey 已刷新成功: rotateOk={}", rotateOk);
                    }
                } else {
                    log.warn("自愈未完全成功，保留错误计数并继续运行: resetOk={}, invalidateKeyOk={}, rotateOk={}",
                        resetOk, invalidateKeyOk, rotateOk);
                }
            }
        });

        return true;
    }

    private static String prefixedReason(String prefix, String reason) {
        return prefix + Texts.nullToEmpty(reason);
    }

    private int autoRestartThreshold(boolean upstreamEmpty) {
        int configured = upstreamEmpty
            ? downloadProperties.getAutoRestart().getUpstreamEmptyErrorThreshold()
            : downloadProperties.getAutoRestart().getErrorThreshold();
        if (configured <= 0) {
            configured = downloadProperties.getAutoRestart().getErrorThreshold();
        }
        return Math.max(1, configured);
    }

    private long autoRestartWindowMs() {
        return Math.max(1L, downloadProperties.getAutoRestart().getWindowMs());
    }

    private long autoRestartMinIntervalMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getMinIntervalMs());
    }

    private long selfHealCooldownMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getSelfHealCooldownMs());
    }

    private static boolean runSelfHealStep(String message, Supplier<Boolean> action) {
        if (action == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(action.get());
        } catch (Throwable t) {
            log.warn("自愈：" + message, t);
            return false;
        }
    }

    private static boolean isUpstreamEmptyReason(String reason) {
        return Texts.hasText(reason)
            && (reason.contains(UpstreamSignedRequestService.REASON_UPSTREAM_EMPTY)
            || reason.contains(UpstreamSignedRequestService.REASON_CHAPTER_EMPTY_OR_SHORT));
    }

    @PreDestroy
    public void destroy() {
        restartExecutor.shutdownNow();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(0);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
