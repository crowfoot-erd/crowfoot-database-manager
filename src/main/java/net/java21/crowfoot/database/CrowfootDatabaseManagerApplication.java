package net.java21.crowfoot.database;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Crowfoot DB 매니저 — 커넥션이 가리키는 데이터베이스의 데이터 조회·편집·SQL 콘솔.
 * DB리스(09-database-manager/00-data-browser.md Section 1.5): 권한 판정·접속 정보·감사는 core 내부 API 경유.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class CrowfootDatabaseManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrowfootDatabaseManagerApplication.class, args);
    }
}
