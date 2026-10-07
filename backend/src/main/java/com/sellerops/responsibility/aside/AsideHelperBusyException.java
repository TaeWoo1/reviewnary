package com.sellerops.responsibility.aside;

import com.sellerops.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * <b>The helper on this desk is already reading something.</b>
 *
 * <p>One live job per device is a schema-level rule, not a politeness: the helper drives one browser profile,
 * and two reads of two marketplaces through one profile is how a page of one store's data ends up measured
 * against another store's expectation.
 *
 * <p>Its own type for the same reason {@link AsideHelperUnavailableException} has one — the two are the same
 * HTTP status and completely different instructions. «연결해 주세요» is something the seller does once and
 * fixes forever; «이미 수집 중입니다» is something they wait out, and a screen that told them to go link a
 * helper they already linked would be sending them to a button that does nothing.
 */
public class AsideHelperBusyException extends ApiException {

    public static final String CODE = "HELPER_BUSY";

    public AsideHelperBusyException() {
        super(HttpStatus.CONFLICT, CODE, "이 컴퓨터에서 이미 확인 작업이 진행 중입니다. 끝난 뒤 다시 눌러 주세요.");
    }
}
