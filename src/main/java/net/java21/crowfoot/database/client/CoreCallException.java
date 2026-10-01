package net.java21.crowfoot.database.client;

import lombok.Getter;

/**
 * core가 도메인 결과(4xx)로 응답한 경우 — core가 보낸 resultCode를 그대로 담는다.
 * 호출 서비스가 자기 계약의 에러 코드로 변환한다 (예: CONNECTION_NOT_FOUND → 404 그대로).
 */
@Getter
public class CoreCallException extends RuntimeException {

    private final String resultCode;

    public CoreCallException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
