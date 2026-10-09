package com.sellerops.review.naver;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.collect.runtime.CollectionMethod;
import com.sellerops.common.ApiException;
import com.sellerops.ingest.IngestFollowUp;
import com.sellerops.ingest.IngestOutcome;
import com.sellerops.ingest.IngestionService;
import com.sellerops.ingest.canonical.CanonicalReview;
import com.sellerops.ingest.canonical.ChannelProductRef;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.coverage.ReviewCoverageCursor;
import com.sellerops.responsibility.IdentityVerdict;
import com.sellerops.responsibility.SourceCompleteness;
import com.sellerops.responsibility.aside.AsideMarketplaceAccess;
import com.sellerops.responsibility.aside.AsideMarketplaceTarget;
import com.sellerops.responsibility.aside.AsideRecipe;
import com.sellerops.responsibility.aside.AsideTrigger;
import com.sellerops.responsibility.aside.ScheduledAsideJob;
import com.sellerops.responsibility.aside.ScheduledAsideJobRepository;
import com.sellerops.responsibility.aside.ScheduledAsideJobStatus;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewReplyState;
import com.sellerops.review.ReviewRepository;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import com.sellerops.sync.SyncJob;
import com.sellerops.sync.SyncJobRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * <b>A scheduled NAVER Seller Center review read, from «which store» to canonical rows.</b>
 *
 * <p>Two halves, and neither is a new pipeline:
 *
 * <ul>
 *   <li><b>{@link #resolve} — which store a job reads.</b> The organisation's one non-file-upload NAVER account that
 *   the deployment named ({@link AsideMarketplaceAccess}). Anything but exactly one is empty, and an empty target
 *   queues no work. No slot and no digest travel for this recipe: the store is judged here at delivery, so there is
 *   nothing the helper needs to compare against.</li>
 *   <li><b>{@link #deliver} — what the read is allowed to become.</b> Strict validation of every row (a row this
 *   service does not understand refuses the whole delivery — half a page stored is indistinguishable from a whole
 *   one), the store fence, and then {@link IngestionService#ingestReviews} — the same spine the export file goes
 *   through, deduplicating on the same {@code external_id}.</li>
 * </ul>
 *
 * <p><b>The store fence.</b> NAVER holds no store identifier this backend stores (H-3), so the fence is the one fact
 * both sides DO hold: 채널상품번호. A listing id is unique per channel across every organisation, and this
 * organisation's NAVER listings were collected by the official API with its own credential. Every product number on
 * the page counted as ours ⇒ {@code MATCH}. Any number that belongs to ANOTHER organisation ⇒ {@code MISMATCH}. Any
 * number nobody holds (a listing newer than the last catalogue read) ⇒ {@code UNRESOLVED}, and nothing is stored —
 * a catalogue lag is not proof of a store. An empty page proves no store either, and is refused the same way.
 *
 * <p><b>«new» and «changed» are counted here and nowhere else.</b> New is the rows ingest inserted. Changed is the
 * already-stored reviews whose reply state this read advanced — the one field a re-read may update. Both are written
 * against the job once, so the run's source row quotes ingest rather than a number the helper computed.
 */
@Service
public class NaverReviewObservationService implements AsideMarketplaceTarget {

    private static final Logger log = LoggerFactory.getLogger(NaverReviewObservationService.class);

    static final String NAVER = "NAVER";
    /** The seller's days — the same zone the coverage cursor keeps its boundary in. */
    private static final java.time.ZoneId KST = ReviewCoverageCursor.KST;
    /** The screen's model held 52 rows for 7 days on the measured store; this bounds a read, not a store. */
    static final int MAX_REVIEWS = 500;
    static final int MAX_BODY_CHARS = 5000;
    static final int MAX_ATTACHMENTS = 50;
    /** 리뷰글번호 as the export and the detail link carry it — 10 digits on every one of 4,432 stored rows. */
    private static final Pattern REVIEW_ID = Pattern.compile("^\\d{10}$");
    private static final Pattern PRODUCT_NO = Pattern.compile("^\\d{1,20}$");

    private final AsideMarketplaceAccess access;
    private final SellerAccountRepository accounts;
    private final ChannelRepository channels;
    private final ChannelProductRepository listings;
    private final ScheduledAsideJobRepository jobs;
    private final ReviewRepository reviews;
    private final IngestionService ingestion;
    private final IngestFollowUp followUp;
    private final SyncJobRepository syncJobs;
    private final com.sellerops.product.ProductRepository products;

    public NaverReviewObservationService(AsideMarketplaceAccess access, SellerAccountRepository accounts,
                                         ChannelRepository channels, ChannelProductRepository listings,
                                         ScheduledAsideJobRepository jobs, ReviewRepository reviews,
                                         IngestionService ingestion, IngestFollowUp followUp,
                                         SyncJobRepository syncJobs, com.sellerops.product.ProductRepository products) {
        this.products = products;
        this.access = access;
        this.accounts = accounts;
        this.channels = channels;
        this.listings = listings;
        this.jobs = jobs;
        this.reviews = reviews;
        this.ingestion = ingestion;
        this.followUp = followUp;
        this.syncJobs = syncJobs;
    }

    /** Where attachment references go. Optional so a context without the media lane stores reviews as before. */
    private com.sellerops.review.media.ReviewMediaWriter media;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMediaWriter(com.sellerops.review.media.ReviewMediaWriter media) {
        this.media = media;
    }

    @Override
    public Optional<Target> resolve(UUID orgId, AsideRecipe recipe) {
        if (orgId == null || recipe != AsideRecipe.NAVER_REVIEW_OBSERVE_V1 || !access.allows(recipe, orgId)) {
            return Optional.empty();
        }
        List<SellerAccount> named = screenAccounts(orgId).stream()
                .filter(a -> access.allowsAccount(a.getId()))
                .toList();
        if (named.size() != 1) {
            return Optional.empty();
        }
        return resolveFor(orgId, named.get(0).getId(), recipe);
    }

    /**
     * One named store, for a seller who pressed on it. The deployment allow-list is not asked here — see
     * {@link AsideMarketplaceTarget#resolveFor}. What IS asked is everything that makes the read meaningful:
     * this organisation's account, on NAVER, that has a screen at all.
     */
    @Override
    public Optional<Target> resolveFor(UUID orgId, UUID sellerAccountId, AsideRecipe recipe) {
        if (orgId == null || sellerAccountId == null || recipe != AsideRecipe.NAVER_REVIEW_OBSERVE_V1) {
            return Optional.empty();
        }
        return screenAccounts(orgId).stream()
                .filter(a -> sellerAccountId.equals(a.getId()))
                .findFirst()
                .map(a -> new Target(a.getId(), null, null));
    }

    /** This organisation's NAVER accounts that have a seller-centre screen to read (file-upload ones do not). */
    private List<SellerAccount> screenAccounts(UUID orgId) {
        Map<UUID, String> codeByChannel = channels.findAll().stream()
                .collect(Collectors.toMap(Channel::getId, Channel::getCode, (a, b) -> a));
        return accounts.findAllByOrgId(orgId).stream()
                .filter(a -> !a.isFileUpload())
                .filter(a -> NAVER.equals(codeByChannel.get(a.getChannelId())))
                .toList();
    }

    /**
     * Take in one job's reading. Order: job → store → rows → identity → ingest → counts. A delivery that fails any
     * gate before identity stores nothing and leaves the job undelivered; one that fails identity records the verdict
     * and stores nothing.
     */
    public NaverReviewObservationView deliver(UUID orgId, UUID deviceId, UUID jobId,
                                              NaverReviewObservationRequest request) {
        ScheduledAsideJob job = jobs.findByIdAndDeviceId(jobId, deviceId)
                .filter(j -> orgId.equals(j.getOrgId()))
                .orElseThrow(() -> ApiException.notFound("해당 작업을 찾을 수 없습니다."));
        Instant now = Instant.now();
        if (job.getRecipe() != AsideRecipe.NAVER_REVIEW_OBSERVE_V1) {
            throw ApiException.badRequest("이 작업은 네이버 리뷰 확인 작업이 아닙니다.");
        }
        if (job.getStatus() != ScheduledAsideJobStatus.CLAIMED || job.getLeaseUntil() == null
                || !job.getLeaseUntil().isAfter(now)) {
            throw ApiException.conflict("진행 중인 작업이 아닙니다.");
        }
        if (job.getIdentityVerdict() != null) {
            // One claim, one delivery. A second one would be a second write of the same reading.
            throw ApiException.conflict("이미 결과를 전달한 작업입니다.");
        }
        // The row named its store when it was queued; re-deriving it from the deployment allow-list here is what
        // made a seller-pressed read impossible to deliver. Older rows carry no account and still re-derive.
        UUID accountId = (job.getSellerAccountId() != null
                ? resolveFor(orgId, job.getSellerAccountId(), job.getRecipe())
                : resolve(orgId, job.getRecipe()))
                .map(Target::sellerAccountId)
                .orElseThrow(() -> ApiException.conflict("이 계정에서는 네이버 리뷰를 확인할 수 없습니다."));
        SellerAccount account = accounts.findByIdAndOrgId(accountId, orgId)
                .orElseThrow(() -> ApiException.notFound("판매 계정을 찾을 수 없습니다."));
        Channel channel = channels.findById(account.getChannelId())
                .filter(c -> NAVER.equals(c.getCode()))
                .orElseThrow(() -> ApiException.badRequest("네이버 판매 계정이 아닙니다."));

        List<NaverReviewObservationRequest.Review> rows = validated(request);
        IdentityVerdict verdict = storeVerdict(orgId, channel.getId(), rows, account, request);
        if (verdict != IdentityVerdict.MATCH) {
            job.refuseDelivery(verdict);
            jobs.save(job);
            log.info("naver review observation: refused job={} identity={} received={}", jobId, verdict, rows.size());
            return new NaverReviewObservationView(verdict.name(), rows.size(), 0, 0, 0, 0);
        }

        rememberScreenStore(account, rows, request);
        int changed = replyStateAdvances(orgId, channel.getId(), rows);
        List<CanonicalReview> canonical = canonical(rows, catalogSkus(channel.getId(), rows));
        Instant startedAt = Instant.now();
        IngestOutcome outcome = ingestion.ingestReviews(orgId, channel.getId(), canonical);
        followUp.afterReviewIngest(orgId, channel.getId(), outcome.insertedIds());
        SyncJob record = recordRead(orgId, channel.getId(), accountId, rows.size(), outcome, request.windowDays(),
                startedAt, job.getTrigger());
        if (record != null) {
            ingestion.stampAcquisition(orgId, outcome.insertedIds(), record.getId());
        }
        int mediaStored = recordMedia(orgId, channel.getId(), rows, now);
        int inserted = outcome.insertedIds().size();
        job.recordDelivery(inserted, changed);
        recordCoverage(job, request, rows.size());
        jobs.save(job);
        // Counts and closed words only. The rows are in hand here, which is exactly why they are not in this line.
        log.info("naver review observation: job={} identity=MATCH received={} inserted={} changed={} skipped={} "
                        + "failed={} windowDays={} media={}",
                jobId, rows.size(), inserted, changed, outcome.skipped(), outcome.failed(), request.windowDays(),
                mediaStored);
        return new NaverReviewObservationView(IdentityVerdict.MATCH.name(), rows.size(), inserted, changed,
                outcome.skipped(), outcome.failed());
    }

    /** Every row understood, or none stored. A shape this service does not recognise is a changed page. */
    static List<NaverReviewObservationRequest.Review> validated(NaverReviewObservationRequest request) {
        if (request == null || request.reviews() == null) {
            throw ApiException.badRequest("리뷰 목록이 필요합니다.");
        }
        List<NaverReviewObservationRequest.Review> rows = request.reviews();
        if (rows.size() > MAX_REVIEWS) {
            throw ApiException.badRequest("한 번에 전달할 수 있는 리뷰 수를 넘었습니다.");
        }
        if (request.windowDays() == null || request.windowDays() < 1 || request.windowDays() > 366) {
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
        Set<String> seen = new HashSet<>();
        for (NaverReviewObservationRequest.Review row : rows) {
            if (row == null || row.reviewId() == null || !REVIEW_ID.matcher(row.reviewId()).matches()
                    || !seen.add(row.reviewId())) {
                throw ApiException.badRequest("리뷰 식별자가 올바르지 않습니다.");
            }
            if (row.rating() == null || row.rating() < 1 || row.rating() > 5) {
                throw ApiException.badRequest("리뷰 평점이 올바르지 않습니다.");
            }
            if (row.productNo() == null || !PRODUCT_NO.matcher(row.productNo()).matches()) {
                throw ApiException.badRequest("상품 번호가 올바르지 않습니다.");
            }
            if (row.body() == null || row.body().length() > MAX_BODY_CHARS) {
                throw ApiException.badRequest("리뷰 본문이 올바르지 않습니다.");
            }
            if (row.answered() == null) {
                // The page states it for every row; a row without it is not a row this reader understood.
                throw ApiException.badRequest("답글 여부가 없습니다.");
            }
            if (row.attachCount() == null || row.attachCount() < 0 || row.attachCount() > MAX_ATTACHMENTS) {
                throw ApiException.badRequest("첨부 수가 올바르지 않습니다.");
            }
            if (row.attachments() != null) {
                // The addresses and the count are two readings of one list; disagreeing, the page moved.
                if (row.attachments().size() != row.attachCount()) {
                    throw ApiException.badRequest("첨부 목록과 첨부 수가 맞지 않습니다.");
                }
                for (NaverReviewObservationRequest.Attachment a : row.attachments()) {
                    if (a == null || a.url() == null || kindOf(a.kind()) == null) {
                        throw ApiException.badRequest("첨부 정보가 올바르지 않습니다.");
                    }
                }
            }
            parseCreatedAt(row.createdAt());
        }
        return rows;
    }

    static com.sellerops.review.media.ReviewMedia.Kind kindOf(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "IMAGE" -> com.sellerops.review.media.ReviewMedia.Kind.IMAGE;
            case "VIDEO" -> com.sellerops.review.media.ReviewMedia.Kind.VIDEO;
            case "UNKNOWN" -> com.sellerops.review.media.ReviewMedia.Kind.UNKNOWN;
            default -> null;
        };
    }

    /**
     * The attachments' addresses, onto the canonical review they belong to — found by the review's own id, after
     * ingest, so a review stored by an earlier read or by the export gains its media on this one.
     */
    private int recordMedia(UUID orgId, UUID channelId, List<NaverReviewObservationRequest.Review> rows,
                            Instant at) {
        if (media == null) {
            return 0;
        }
        int stored = 0;
        for (NaverReviewObservationRequest.Review row : rows) {
            if (row.attachments() == null || row.attachments().isEmpty()) {
                continue;
            }
            Optional<Review> review = reviews.findByOrgIdAndChannelIdAndExternalId(orgId, channelId, row.reviewId());
            if (review.isEmpty()) {
                continue;
            }
            stored += media.record(orgId, review.get().getId(), row.attachments().stream()
                            .map(a -> new com.sellerops.review.media.ReviewMediaWriter.Attachment(a.url(),
                                    kindOf(a.kind())))
                            .toList(),
                    AsideRecipe.NAVER_REVIEW_OBSERVE_V1.name(), at);
        }
        return stored;
    }

    /** MATCH only when every product number on the page is this organisation's; see the class comment. */
    IdentityVerdict storeVerdict(UUID orgId, UUID channelId, List<NaverReviewObservationRequest.Review> rows) {
        return com.sellerops.responsibility.aside.AsideCatalogueFence.verdict(listings, orgId, channelId,
                rows.stream().map(NaverReviewObservationRequest.Review::productNo).toList());
    }

    /**
     * <b>행이 있으면 카탈로그가, 없으면 화면 지문이 답한다.</b>
     *
     * <p>가게 확인의 증거는 페이지의 상품번호다 — 전부 이 조직의 것이면 MATCH. 그런데 리뷰가 한 건도 없는
     * 기간에는 그 증거가 존재하지 않고(2026-10-10 실측: 새벽에 「오늘」을 읽으면 늘 그렇다), 그 자리에서
     * 멈추면 저판매량 매장의 빈 날은 영원히 닫히지 않는다.
     *
     * <p>그래서 두 번째 증거를 쓴다: 판매자 센터 chrome에 행과 무관하게 늘 찍혀 있는 것들의 digest.
     * <b>그 값은 스스로를 증명하지 않는다</b> — 카탈로그가 MATCH를 낸 읽기에서만 저장되고
     * ({@link #rememberScreenStore}), 여기서는 저장된 값과 <b>같은지</b>만 묻는다. 저장된 것이 없거나
     * 다르면 UNRESOLVED이고, 그것은 실패가 아니라 「이 빈 기간은 귀속할 수 없다」이다.
     */
    private IdentityVerdict storeVerdict(UUID orgId, UUID channelId,
                                         List<NaverReviewObservationRequest.Review> rows,
                                         SellerAccount account, NaverReviewObservationRequest request) {
        if (!rows.isEmpty()) {
            return storeVerdict(orgId, channelId, rows);
        }
        if (!Boolean.TRUE.equals(request.emptyPeriod())) {
            // 0행인데 화면이 「없다」고 말한 적은 없다. 예전과 같은 답을 준다.
            return IdentityVerdict.UNRESOLVED;
        }
        String seen = screenStoreDigest(request);
        String known = screenStoreDigest(account.getStoreIdentity());
        return seen != null && seen.equals(known) ? IdentityVerdict.MATCH : IdentityVerdict.UNRESOLVED;
    }

    /**
     * <b>증명된 읽기만이 이 계정의 화면이 어떻게 생겼는지 가르칠 수 있다.</b>
     *
     * <p>카탈로그가 MATCH를 낸 읽기 — 즉 그 화면의 모든 상품번호가 이 조직의 것이라고 독립적으로 확인된
     * 순간 — 에서만 지문을 적는다. 화면이 달라졌다고 해서 갱신하지 않는 것이 이 설계의 전부다: 그러면
     * 화면이 스스로를 승인하게 되고, 아이디나 스토어명이 바뀐 경우의 회복은 <b>다음 번 증명된 읽기</b>가
     * 자연히 가져다준다.
     *
     * <p>원문은 저장하지 않는다. 들어오는 것도 digest이고 보관하는 것도 그 digest다.
     */
    private void rememberScreenStore(SellerAccount account, List<NaverReviewObservationRequest.Review> rows,
                                     NaverReviewObservationRequest request) {
        if (rows.isEmpty()) {
            return;
        }
        String seen = screenStoreDigest(request);
        if (seen == null || seen.equals(account.getStoreIdentity())) {
            return;
        }
        account.setStoreIdentity(seen);
        accounts.save(account);
        log.info("naver review observation: screen store fingerprint recorded account={}", account.getId());
    }

    /** 64자 소문자 hex, 또는 null. 그 모양이 아닌 것은 없는 것으로 친다. */
    private static String screenStoreDigest(NaverReviewObservationRequest request) {
        return screenStoreDigest(request == null ? null : request.screenStoreDigest());
    }

    private static String screenStoreDigest(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        return value.length() == 64 && value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
                ? value : null;
    }

    /** Stored reviews whose reply state this reading moves forward — the only field a re-read may change. */
    private int replyStateAdvances(UUID orgId, UUID channelId, List<NaverReviewObservationRequest.Review> rows) {
        int changed = 0;
        for (NaverReviewObservationRequest.Review row : rows) {
            Optional<Review> stored = reviews.findByOrgIdAndChannelIdAndExternalId(orgId, channelId, row.reviewId());
            if (stored.isPresent() && ReviewReplyState.isProgress(stored.get().getReplyState(), replyStateOf(row))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 채널상품번호 → the SKU of the product that listing already belongs to, find-only.
     *
     * <p>Ingest attributes a declaring row by the SKU its caller resolved (the Coupang handoff does the same), so a
     * row sent with no SKU is stored unlinked even when the catalogue holds its listing — found on the first live
     * run: 52 reviews stored, all 52 listings present, product link 0. The value that travels on is read out of this
     * database, never one the page supplied, and a listing with no product stays unlinked rather than invented.
     */
    private Map<String, String> catalogSkus(UUID channelId, List<NaverReviewObservationRequest.Review> rows) {
        Map<String, String> skus = new java.util.HashMap<>();
        for (NaverReviewObservationRequest.Review row : rows) {
            if (skus.containsKey(row.productNo())) {
                continue;
            }
            String sku = listings.findByChannelIdAndExternalProductId(channelId, row.productNo())
                    .map(com.sellerops.product.ChannelProduct::getProductId)
                    .flatMap(products::findById)
                    .map(com.sellerops.product.Product::getSku)
                    .orElse(null);
            skus.put(row.productNo(), sku);
        }
        return skus;
    }

    private static List<CanonicalReview> canonical(List<NaverReviewObservationRequest.Review> rows,
                                                   Map<String, String> skus) {
        List<CanonicalReview> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            NaverReviewObservationRequest.Review row = rows.get(i);
            String body = row.body();
            out.add(new CanonicalReview(
                    row.productName(),
                    skus.get(row.productNo()),
                    row.rating(),
                    body,
                    parseCreatedAt(row.createdAt()),
                    // The review's own id — the export's 리뷰글번호 — so a review read here and later exported (or
                    // exported first and read here) is ONE canonical row, deduplicated by the spine's ext: key.
                    row.reviewId(),
                    i + 1,
                    replyStateOf(row),
                    null,
                    null,
                    row.attachCount(),
                    // The row model listed its attachments, so this count is a reading rather than a default.
                    true,
                    body.isBlank(),
                    // Attribute by the channel's own listing id, find-only: never a name fallback, never a product
                    // created from a value the page printed.
                    ChannelProductRef.of(row.productNo())));
        }
        return out;
    }

    private static ReviewReplyState replyStateOf(NaverReviewObservationRequest.Review row) {
        return Boolean.TRUE.equals(row.answered()) ? ReviewReplyState.ANSWERED : ReviewReplyState.PENDING;
    }

    private static Instant parseCreatedAt(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("리뷰 등록일이 올바르지 않습니다.");
        }
    }

    /**
     * <b>What this read proved about days — written beside the read, judged here.</b>
     *
     * <p>Three things go on the row: the period the screen showed, the ceiling the reading was under, and whether
     * the two together exclude 「이 기간에 더 있을 수 있음」. The judgement is a comparison rather than a flag the
     * helper sets, so it can be re-checked against the stored numbers later — and so a helper cannot assert a
     * completeness it has no way to know.
     *
     * <p><b>Saturation is not failure.</b> A read at its ceiling still stored real rows, and they stay. What it may
     * not do is move a boundary: {@code PARTIAL} here is how the coverage cursor is told 「이 기간은 아직
     * 빠짐없다고 말할 수 없다」, and the planner answers by splitting the period rather than retrying it whole.
     *
     * <p>A read that names no period writes nothing at all — the pre-2026-10-08 helper's shape. Null is 「모른다」,
     * and a date computed from this process's clock for a period this process did not see would be exactly the
     * invented evidence the cursor refuses.
     */
    private void recordCoverage(ScheduledAsideJob job, NaverReviewObservationRequest request, int rows) {
        LocalDate start = parseDay(request.windowStart());
        LocalDate end = parseDay(request.windowEnd());
        if (start == null || end == null) {
            if (job.getRequestedWindowStart() != null) {
                // Asked for a named period and handed back a reading that cannot say which days it covered.
                // Storing it would leave the parent to assume the window it requested.
                throw ApiException.badRequest("확인한 기간을 알 수 없습니다.");
            }
            return;
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
        LocalDate today = LocalDate.now(KST);
        if (end.isAfter(today)) {
            // A period that ends in the future is not a period anyone read.
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
        if (request.windowDays() != null && end.toEpochDay() - start.toEpochDay() + 1 != request.windowDays()) {
            // The two statements of the same bound must agree; a length that contradicts its own dates means the
            // reading of the screen's period is not one fact.
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
        Integer capacity = request.rowCapacity();
        if (capacity != null && (capacity < 1 || capacity < rows)) {
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
        // <b>The period asked for and the period read must be the same period.</b> The helper proves it against
        // the screen's own controls before it reads a row; this proves it again against what was requested,
        // because the two checks fail for different reasons — one catches a screen that did not move, the other
        // catches a delivery arriving for a window nobody asked about. A catch-up counting a window it did not
        // read is how a coverage boundary gets ahead of the data.
        if (job.getRequestedWindowStart() != null
                && (!job.getRequestedWindowStart().equals(start) || !job.getRequestedWindowEnd().equals(end))) {
            throw ApiException.badRequest("확인한 기간이 요청한 기간과 다릅니다.");
        }
        job.setWindowStart(start);
        job.setWindowEnd(end);
        job.setObservedCapacity(capacity);
        job.setLabelledTotal(request.labelledTotal());
        job.setSelectedPageSize(request.selectedPageSize());
        job.setGridReadMode(request.gridReadMode());
        job.setMonthMoves(request.monthMoves());
        String reason = incompleteReason(request, rows, capacity);
        // <b>BOUNDED, not COMPLETE.</b> A window read stops at a bound it recorded; it never reaches the end of
        // what the store holds. The column's check constraint says the same thing, and on 2026-10-09 it said it
        // by rejecting a read that had worked: 42 rows, the window verified against the screen, the screen's own
        // total agreeing — and the delivery thrown away at commit time over a word.
        job.recordCoverageVerdict(
                reason == null ? SourceCompleteness.BOUNDED : SourceCompleteness.PARTIAL,
                reason == null ? "WHOLE_PERIOD_READ" : reason);
    }

    /**
     * <b>Why this reading may NOT be called the whole period — or null when every test passed.</b>
     *
     * <p>The rule it replaces was one comparison: {@code rows < rowCapacity}. That is a statement about our own
     * ceiling and nothing else, and reading it as coverage is how «45 rows, capacity 500» became «those seven
     * days are covered» on 2026-10-08 while twenty-nine days behind it were untouched. A ceiling that was not
     * reached excludes one way of missing rows. It does not establish that the screen was showing the period.
     *
     * <p>So four facts have to agree, each from a different place, and any one of them being unavailable is
     * itself a reason to leave the boundary where it is:
     *
     * <ol>
     *   <li><b>the ceiling was not reached</b> — ours, as before;
     *   <li><b>the rows came from the grid's own row model</b> — the list recycles about fifteen DOM rows, so
     *       any reading that is not the model's is a reading of a viewport, however many times it scrolled;
     *   <li><b>the screen's printed total equals what arrived</b> — the independent witness. A model that
     *       loaded 42 of 46 is caught here and nowhere else;
     *   <li><b>that total is within the page size the list was set to</b> — a total above it could not have
     *       been on one page whatever loaded, and the page size is the seller's own setting, never changed by
     *       a read.
     * </ol>
     *
     * <p>A window that fails any of these keeps its rows — they were really read — and moves no boundary. That
     * is the saturation rule the catch-up walk already relies on, applied to the one case it could not see.
     */
    static String incompleteReason(NaverReviewObservationRequest request, int rows, Integer capacity) {
        if (capacity == null) {
            return "CAPACITY_UNKNOWN";
        }
        if (rows >= capacity) {
            return "CAPACITY_REACHED";
        }
        // <b>`MODEL`은 「그리드의 행 모델에서 전부 읽었다」이고, `EMPTY_STATE`는 「그리드가 없다고 말했다」다.</b>
        // 둘 다 그 기간 전체에 대한 답이지만, 두 번째는 행이 0일 때만 그렇다 — 행을 건네면서 비었다고 하는
        // 읽기는 자기 모순이고, 그 주장으로 경계를 옮길 수는 없다.
        if (!"MODEL".equals(request.gridReadMode())
                && !("EMPTY_STATE".equals(request.gridReadMode()) && rows == 0)) {
            return "READ_MODE_UNPROVEN";
        }
        Integer total = request.labelledTotal();
        if (total == null) {
            return "TOTAL_UNREADABLE";
        }
        if (total != rows) {
            return "TOTAL_DISAGREES";
        }
        Integer pageSize = request.selectedPageSize();
        if (pageSize == null) {
            return "PAGE_SIZE_UNREADABLE";
        }
        if (total > pageSize) {
            return "TOTAL_ABOVE_PAGE_SIZE";
        }
        return null;
    }

    private static LocalDate parseDay(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("확인한 기간이 올바르지 않습니다.");
        }
    }

    /**
     * The read's own import record. {@code PARTIAL} with the bound named, because a read of the screen's period is
     * not a completed import of the store — the same rule the Coupang handoff applies to a walk that did not reach
     * the end of its list.
     *
     * <p><b>The trigger is the job's own.</b> It was the constant {@code "RESPONSIBILITY"} until 2026-10-08, so the
     * import record of a read a seller pressed named the autonomous lane — measured on job {@code 7bde2cb5}, whose
     * {@code trigger_source} was {@code OPERATOR} while the run it wrote said otherwise. Asking «who authorised
     * this read» of {@code sync_jobs} then gave the wrong answer, which is the one question that record exists to
     * answer.
     */
    private SyncJob recordRead(UUID orgId, UUID channelId, UUID accountId, int received, IngestOutcome outcome,
                               Integer windowDays, Instant startedAt, AsideTrigger trigger) {
        try {
            SyncJob job = new SyncJob();
            job.setOrgId(orgId);
            job.setChannelId(channelId);
            job.setSellerAccountId(accountId);
            job.setDataType("REVIEW");
            job.setUploadType("REVIEW");
            job.setJobType("SCHEDULED_ASIDE_READ");
            job.setMethod(CollectionMethod.SELLER_CENTER_READ.name());
            job.setTrigger((trigger != null ? trigger : AsideTrigger.RESPONSIBILITY).name());
            job.setStartedAt(startedAt);
            job.setFinishedAt(Instant.now());
            job.setTotalRows(received);
            job.setSuccessRows(outcome.success());
            job.setSkippedRows(outcome.skipped());
            job.setFailedRows(outcome.failed());
            job.setStatus("PARTIAL");
            job.setErrorMessage("BOUNDED_WINDOW_DAYS_" + windowDays);
            return syncJobs.save(job);
        } catch (RuntimeException e) {
            log.warn("naver review observation stored, import history not recorded: type={}",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
