package net.java21.crowfoot.database.edit;

import lombok.Getter;
import net.java21.crowfoot.database.common.error.BusinessException;
import net.java21.crowfoot.database.common.error.ErrorCode;

/**
 * 변경 한 건이 실패했다 (00-data-browser.md Section 3.5) — 몇 번째 변경인지를 응답의 errors에 싣는다.
 * 이 예외가 나면 그 요청의 변경은 전부 되돌린다.
 */
@Getter
public class ChangeFailedException extends BusinessException {

    /** 요청의 변경 번호(0부터) */
    private final int index;
    /** 데이터베이스가 돌려준 문구 — 없으면 null */
    private final String detail;

    public ChangeFailedException(ErrorCode errorCode, int index, String detail) {
        super(errorCode);
        this.index = index;
        this.detail = detail;
    }
}
