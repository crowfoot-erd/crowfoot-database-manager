package net.java21.crowfoot.database.support;

import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;
import net.java21.crowfoot.database.config.LimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사용자당 동시 실행 수 제한 (00-data-browser.md Section 2.3).
 */
class UserConcurrencyLimiterTest {

    private final UserConcurrencyLimiter limiter = new UserConcurrencyLimiter(new LimitsProperties(
            Duration.ofSeconds(5), Duration.ofSeconds(8), 100, 500, 500, 1000, 2000, 1_000_000,
            5 * 1024 * 1024, 10, 3, 100, 100_000, 2));

    @Test
    @DisplayName("한 사용자의 세 번째 동시 실행은 TOO_MANY_REQUESTS — 다른 사용자는 영향이 없고, 끝나면 다시 받는다")
    void limitsPerUser() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> limiter.run(1L, () -> hold(started, release)));
            Future<String> second = pool.submit(() -> limiter.run(1L, () -> hold(started, release)));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> limiter.run(1L, () -> "third"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
            assertThat(limiter.run(2L, () -> "other user")).isEqualTo("other user");

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(limiter.run(1L, () -> "again")).isEqualTo("again");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("작업이 예외로 끝나도 자리를 돌려준다")
    void releasesOnFailure() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.run(1L, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(limiter.run(1L, () -> "ok")).isEqualTo("ok");
    }

    private static String hold(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "done";
    }
}
