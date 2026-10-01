package net.java21.crowfoot.database.query;

import lombok.Getter;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;

/**
 * 쓰기·구조 문장이 사용자 확인 없이 왔다 (00-data-browser.md Section 3.6) —
 * 응답에 문장 종류를 실어, 화면이 확인 다이얼로그에 보여 주게 한다.
 */
@Getter
public class ConfirmationRequiredException extends BusinessException {

    private final StatementKind kind;

    public ConfirmationRequiredException(StatementKind kind) {
        super(ErrorCode.CONFIRMATION_REQUIRED);
        this.kind = kind;
    }
}
