package com.sellerops.responsibility.aside;

import com.sellerops.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * <b>There is no helper on this organisation's desk to give work to.</b>
 *
 * <p>Its own type because the two lanes must answer it differently and neither answer is «something went
 * wrong». A responsibility run turns it into {@code NONE / DEVICE_OFFLINE} — nothing was read, which is not
 * zero. The operator lane turns it into a screen that says 「도우미를 이 계정에 한 번 연결해 주세요」, because that
 * is a thing the seller can do and the only thing that will help.
 *
 * <p>The {@code code} is what a client branches on, so a rewritten Korean sentence cannot change behaviour.
 */
public class AsideHelperUnavailableException extends ApiException {

    public static final String CODE = "HELPER_NOT_LINKED";

    public AsideHelperUnavailableException() {
        super(HttpStatus.CONFLICT, CODE, "이 계정에 연결된 도우미가 없습니다. 도우미를 한 번 연결해 주세요.");
    }
}
