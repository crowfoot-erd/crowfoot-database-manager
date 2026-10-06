package net.java21.crowfoot.database.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * DB 매니저 에러 코드 (09-database-manager/00-data-browser.md Section 4 + 01-architecture/api-design.md Section 5.4).
 *
 * <p>core와 이름이 같은 코드(WORKSPACE_NOT_FOUND·CONNECTION_NOT_FOUND·PERMISSION_DENIED·CONNECTION_UNREACHABLE)는
 * 상태코드와 뜻도 core와 같다 — 접근 확인(core 내부 API)의 판정을 그대로 전달한다.
 */
@Getter
public enum ErrorCode {

    // 공통 (api-design.md Section 5.4)
    SUCCESS(HttpStatus.OK, "SUCCESS", "SUCCESS"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "리소스를 찾을 수 없습니다"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다"),
    // Gateway를 거치지 않은 요청(X-USER-ID 없음) — core XUserIdFilter와 같은 코드
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "토큰이 유효하지 않습니다"),

    // 접근 확인 — core 판정 전달 (08-core/15-internal-api.md Section 2.1)
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "워크스페이스를 찾을 수 없습니다"),
    CONNECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND", "커넥션을 찾을 수 없습니다"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "권한이 없습니다"),

    // 데이터 브라우저 (00-data-browser.md Section 4)
    CONNECTION_UNREACHABLE(HttpStatus.BAD_GATEWAY, "CONNECTION_UNREACHABLE", "데이터베이스에 접속할 수 없습니다"),
    OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", "테이블 또는 뷰를 찾을 수 없습니다"),
    OBJECT_NOT_EDITABLE(HttpStatus.CONFLICT, "OBJECT_NOT_EDITABLE", "이 객체는 편집할 수 없습니다"),
    INVALID_VALUE(HttpStatus.BAD_REQUEST, "INVALID_VALUE", "값이 컬럼 타입에 맞지 않습니다"),
    GENERATED_COLUMN(HttpStatus.BAD_REQUEST, "GENERATED_COLUMN", "생성 컬럼에는 값을 넣을 수 없습니다"),
    ROW_CONFLICT(HttpStatus.CONFLICT, "ROW_CONFLICT", "대상 행이 없거나 그 사이에 바뀌었습니다"),
    ROW_CHANGE_FAILED(HttpStatus.CONFLICT, "ROW_CHANGE_FAILED", "데이터베이스가 변경을 거부했습니다"),
    VALUE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "VALUE_TOO_LARGE", "값이 너무 큽니다"),
    QUERY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT", "실행 제한 시간을 넘어 취소했습니다"),
    QUERY_FAILED(HttpStatus.CONFLICT, "QUERY_FAILED", "데이터베이스가 조회를 거부했습니다"),
    MULTIPLE_STATEMENTS(HttpStatus.BAD_REQUEST, "MULTIPLE_STATEMENTS", "한 번에 한 문장만 실행할 수 있습니다"),
    UNSUPPORTED_STATEMENT(HttpStatus.BAD_REQUEST, "UNSUPPORTED_STATEMENT", "지원하지 않는 문장입니다"),
    CONFIRMATION_REQUIRED(HttpStatus.CONFLICT, "CONFIRMATION_REQUIRED", "실행 전에 확인이 필요합니다"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "실행 중인 요청이 끝난 뒤 다시 시도하세요");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 번들 키 — i18n/messages_{ko,en,ja,zh}.properties의 error.{code} (api-design.md §5.7, core 미러) */
    public String messageKey() {
        return "error." + code.toLowerCase();
    }
}
