package com.sellerops.vendor.install;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.mail.Mailer;
import com.sellerops.vendor.mail.OutboundMail;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The two mails a PC link sends — contract §2. The step-up code before it, and after it the notice that a PC was
 * linked: when, what kind of PC, and what to do if it was not the seller. The notice carries no link that unlinks by
 * itself (2026-10-22 review): a mail can be forwarded or previewed by a scanner, and an unlink is the operator's after
 * the seller says so. Neither mail carries anything about reviews.
 */
@Component
public class InstallNotices {

    private static final Logger log = LoggerFactory.getLogger(InstallNotices.class);
    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Seoul"));

    private final Mailer mailer;
    private final SellerAccounts accounts;
    private final Clock clock;

    @Autowired
    public InstallNotices(Mailer mailer, SellerAccounts accounts) {
        this(mailer, accounts, Clock.systemUTC());
    }

    InstallNotices(Mailer mailer, SellerAccounts accounts, Clock clock) {
        this.mailer = mailer;
        this.accounts = accounts;
        this.clock = clock;
    }

    static OutboundMail codeMail(String to, String code, String deviceName) {
        return new OutboundMail(to, "[reviewnary] PC 연결 확인 코드 " + code,
                "PC 연결 확인 코드: " + code + "\n\n"
                        + "PC 종류: " + deviceName + "\n"
                        + "10분 안에 PC 연결 화면에 입력하세요. 이 코드는 지금 요청한 그 PC 한 대에만 쓰입니다.\n\n"
                        + "직접 요청하지 않았다면 코드를 어디에도 입력하지 마세요. 누군가 이 계정의 비밀번호를 알고 있다는 "
                        + "뜻이니 reviewnary 운영자에게 바로 알려 주세요.\n");
    }

    /** Best effort: a notice that does not go out never undoes the link. Nothing about the mail is logged. */
    void linked(UUID accountId, String deviceName) {
        Instant now = clock.instant();
        accounts.emailOf(accountId).ifPresent(to -> {
            try {
                mailer.send(new OutboundMail(to, "[reviewnary] 새 PC가 연결됐습니다",
                        KST.format(now) + "(한국 시간)에 PC 한 대가 이 계정에 연결됐습니다.\n\n"
                                + "PC 종류: " + deviceName + "\n\n"
                                + "직접 연결했다면 할 일은 없습니다.\n"
                                + "직접 연결하지 않았다면 reviewnary 운영자에게 바로 알려 주세요. 운영자가 그 PC의 연결을 "
                                + "끊고 비밀번호를 다시 정할 수 있게 해 드립니다. 이 메일의 어떤 링크도 누를 필요가 "
                                + "없습니다.\n"));
            } catch (RuntimeException e) {
                log.warn("new-PC notice not sent: type={}", e.getClass().getSimpleName());
            }
        });
    }
}
