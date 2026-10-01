package net.java21.crowfoot.database;

import net.java21.crowfoot.database.config.LimitsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 배선 — 자기 DB 없이(DataSource 없이) 뜨고, 한도 설정이 바인딩된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationBootTest {

    @Autowired
    private LimitsProperties limits;

    @Test
    @DisplayName("컨텍스트가 뜨고 한도가 스펙 값으로 바인딩된다 (00-data-browser.md Section 2.3)")
    void bootsWithLimits() {
        assertThat(limits.statementTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(limits.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(limits.pageSizeMax()).isEqualTo(500);
        assertThat(limits.consoleRowsMax()).isEqualTo(1000);
        assertThat(limits.valueLengthMax()).isEqualTo(1_000_000);
        assertThat(limits.concurrentPerUser()).isEqualTo(2);
    }
}
