package net.java21.crowfoot.database.support;

import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 사용자당 동시 실행 수 제한 (00-data-browser.md Section 2.3) — 한 사람의 느린 질의가 요청 처리 스레드를
 * 다 붙잡는 일을 막는다. 한도를 넘으면 기다리지 않고 바로 TOO_MANY_REQUESTS다.
 */
@Component
public class UserConcurrencyLimiter {

    private final ConcurrentHashMap<Long, AtomicInteger> running = new ConcurrentHashMap<>();
    private final int limit;

    public UserConcurrencyLimiter(LimitsProperties limits) {
        this.limit = limits.concurrentPerUser();
    }

    public <T> T run(long userId, Supplier<T> work) {
        AtomicInteger counter = running.computeIfAbsent(userId, key -> new AtomicInteger());
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet();
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        try {
            return work.get();
        } finally {
            // 0이 되면 항목을 지운다 — 사용자 수만큼 맵이 자라지 않게
            if (counter.decrementAndGet() == 0) {
                running.remove(userId, counter);
            }
        }
    }
}
