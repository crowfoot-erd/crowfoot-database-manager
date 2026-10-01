package net.java21.crowfoot.database.jdbc;

import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * 문장 실행 중 SQLException → 계약 에러 (00-data-browser.md Section 4).
 */
public final class SqlErrors {

    private SqlErrors() {
    }

    /** 실행 제한 시간 초과로 취소된 것인지 — 드라이버마다 신호가 다르다 */
    public static boolean isTimeout(SQLException e) {
        if (e instanceof SQLTimeoutException) {
            return true;
        }
        String state = e.getSQLState();
        // PostgreSQL 57014 query_canceled, MySQL 3024(max_execution_time)·1317(query interrupted)
        return "57014".equals(state) || e.getErrorCode() == 3024 || e.getErrorCode() == 1317;
    }

    /** 값이 컬럼 타입으로 변환되지 않는 것인지 — SQLSTATE 22 계열(데이터 예외) */
    public static boolean isInvalidValue(SQLException e) {
        String state = e.getSQLState();
        return state != null && state.startsWith("22");
    }

    /** 조회 문장 실패 — 제한 시간·값 변환·그 밖(데이터베이스 문구 전달) */
    public static BusinessException translateQuery(SQLException e) {
        if (isTimeout(e)) {
            return new BusinessException(ErrorCode.QUERY_TIMEOUT);
        }
        if (isInvalidValue(e)) {
            return new BusinessException(ErrorCode.INVALID_VALUE, messageOf(e));
        }
        return new BusinessException(ErrorCode.QUERY_FAILED, messageOf(e));
    }

    /** 데이터베이스가 돌려준 문구 — 사용자가 조건·SQL을 고치는 데 필요하므로 전달한다(Section 3.6) */
    public static String messageOf(SQLException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? "SQL error" : message.strip();
    }
}
