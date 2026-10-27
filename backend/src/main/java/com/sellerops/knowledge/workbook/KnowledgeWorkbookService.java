package com.sellerops.knowledge.workbook;

import com.sellerops.attention.reply.ReviewReplyTemplateKey;
import com.sellerops.attention.reply.ReviewReplyTemplateService;
import com.sellerops.common.ApiException;
import com.sellerops.knowledge.CustomerPhrases;
import com.sellerops.knowledge.KnowledgeBlanks;
import com.sellerops.knowledge.candidate.KnowledgeCandidate;
import com.sellerops.knowledge.candidate.KnowledgeCandidateRepository;
import com.sellerops.knowledge.candidate.KnowledgeCandidateService;
import com.sellerops.knowledge.org.OrgKnowledgeSource;
import com.sellerops.knowledge.org.OrgKnowledgeSourceRepository;
import com.sellerops.knowledge.org.OrgKnowledgeType;
import com.sellerops.knowledge.org.SellerOperationsKnowledgeService;
import com.sellerops.knowledge.org.dto.OrgKnowledgeRequest;
import com.sellerops.knowledge.style.AnswerLength;
import com.sellerops.knowledge.style.AnswerStyleProfile;
import com.sellerops.knowledge.style.AnswerStyleService;
import com.sellerops.knowledge.style.AnswerTone;
import com.sellerops.knowledge.style.AvoidPhrase;
import com.sellerops.knowledge.style.EmojiPolicy;
import com.sellerops.knowledge.style.dto.AnswerStyleRequest;
import com.sellerops.product.ChannelProduct;
import com.sellerops.product.ChannelProductRepository;
import com.sellerops.product.OperatorProductName;
import com.sellerops.product.Product;
import com.sellerops.product.ProductRepository;
import com.sellerops.product.library.KnowledgeAuthorship;
import com.sellerops.product.library.KnowledgeSourceType;
import com.sellerops.product.library.ProductKnowledgeLibraryService;
import com.sellerops.product.library.ProductKnowledgeSource;
import com.sellerops.product.library.ProductKnowledgeSourceRepository;
import com.sellerops.product.library.dto.KnowledgeSourceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * <b>말투 · 답변 템플릿 · 안내 매뉴얼, as one spreadsheet the seller keeps</b> (owner, 2026-10-27: 「답변 템플릿과
 * 메뉴얼을 … 엑셀 등으로 지정해두고」).
 *
 * <p>The three things a reply is written from that a person decides — how the company sounds (답변 스타일), the wording
 * a review gets when nothing else can be said (review reply templates), and what the company tells a customer about a
 * problem (product and company knowledge, with 「고객은 이렇게 말해요」) — lived on three screens, edited one row at a
 * time. A seller who wants to read them all, hand them to a colleague, or fill twenty at once now downloads one
 * workbook, edits it, and uploads it back.
 *
 * <p><b>Nothing reaches a reply without the seller's 「사용」.</b> Every row carries a 상태. 「사용」 applies it; 「확인 전」 —
 * the state every row Claude or SellerOps proposes starts in — applies nothing to replies: a manual row becomes a
 * proposal in 확인 필요, and a style or template row is left in the file. A row that still holds a 【확인: …】 blank
 * cannot be 「사용」 ({@link KnowledgeBlanks}). 「사용 안 함」 turns a guidance off without deleting it.
 *
 * <p><b>Upload is read first, applied second.</b> {@link #read} with {@code apply=false} says what would change and
 * changes nothing; the same file with {@code apply=true} goes through the ordinary write paths — the style service, the
 * template service, the knowledge services and the candidate inbox — so every validation those screens enforce holds
 * here too, and a refusal from one of them is reported against its row rather than failing the file.
 *
 * <p><b>What changes is shown as it is and as it will be</b> (2026-10-27 review): every planned change carries where it
 * applies and its before and after, so 적용 is pressed on the text, not on 「안내 고침」.
 *
 * <p><b>An old file does not overwrite a newer screen.</b> Each row carries what it was when downloaded — the
 * 「지금 값」 · 「지금 문구」 columns, and a hidden 「변경 확인」 fingerprint on 안내 매뉴얼 rows. A row whose server copy has
 * changed since is not applied; it is reported, with what it says now, and the seller downloads again. A file from
 * before the fingerprint existed has no 「변경 확인」 and its manual rows are not checked.
 *
 * <p>Only what a person wrote is in the file: a document uploaded as a file or read from a channel stays on its own
 * screen.
 */
@Service
public class KnowledgeWorkbookService {

    static final String SHEET_GUIDE = "사용법";
    static final String SHEET_STYLE = "말투";
    static final String SHEET_TEMPLATES = "답변 템플릿";
    static final String SHEET_MANUAL = "안내 매뉴얼";

    static final String USE = "사용";
    static final String PENDING = "확인 전";
    static final String OFF = "사용 안 함";
    static final String DEFAULT = "기본값으로";
    static final String SCREEN = "화면에서 관리";

    static final String SCOPE_PRODUCT = "상품";
    static final String SCOPE_ORG = "회사 전체";

    /** Excel's own limit on one cell is 32,767 characters; a longer document stays on its screen. */
    static final int MAX_CELL = 32_000;

    static final List<String> STYLE_HEADERS = List.of("항목", "지금 값", "새 값", "상태", "설명");
    static final List<String> TEMPLATE_HEADERS = List.of("유형", "언제 쓰나요", "지금 문구", "새 문구", "상태", "관리키");
    static final List<String> MANUAL_HEADERS = List.of("관리번호", "범위", "상품번호", "상품명", "종류", "주제", "안내 문장",
            "고객은 이렇게 말해요", "상태", "같은 문제 리뷰", "메모", "변경 확인");

    /** A row changed on a screen after the file was downloaded. */
    static final String STALE = "내려받은 뒤 화면에서 바뀐 줄이라 반영하지 않았습니다. 새로 내려받아 다시 고쳐 주세요.";
    /** An existing guidance row with no 「변경 확인」 — a file from before it existed, or a cell someone cleared. */
    static final String NO_BASE = "내려받은 때의 내용을 확인할 수 없는 줄이라 반영하지 않았습니다(예전 파일이거나 숨은 칸이 지워졌습니다). "
            + "새로 내려받아 다시 고쳐 주세요.";

    private final AnswerStyleService styles;
    private final ReviewReplyTemplateService templates;
    private final ProductKnowledgeLibraryService productLibrary;
    private final ProductKnowledgeSourceRepository productSources;
    private final SellerOperationsKnowledgeService orgKnowledge;
    private final OrgKnowledgeSourceRepository orgSources;
    private final KnowledgeCandidateService candidateService;
    private final KnowledgeCandidateRepository candidateRows;
    private final ProductRepository products;
    private final ChannelProductRepository listings;

    public KnowledgeWorkbookService(AnswerStyleService styles, ReviewReplyTemplateService templates,
                                    ProductKnowledgeLibraryService productLibrary,
                                    ProductKnowledgeSourceRepository productSources,
                                    SellerOperationsKnowledgeService orgKnowledge,
                                    OrgKnowledgeSourceRepository orgSources,
                                    KnowledgeCandidateService candidateService,
                                    KnowledgeCandidateRepository candidateRows,
                                    ProductRepository products, ChannelProductRepository listings) {
        this.styles = styles;
        this.templates = templates;
        this.productLibrary = productLibrary;
        this.productSources = productSources;
        this.orgKnowledge = orgKnowledge;
        this.orgSources = orgSources;
        this.candidateService = candidateService;
        this.candidateRows = candidateRows;
        this.products = products;
        this.listings = listings;
    }

    /**
     * One line of what an upload would do, or did, or could not do — against the sheet row the seller sees. A change
     * says where it applies and what the text is now and will be; a problem may say what the text is now.
     */
    public record Line(String sheet, int row, String text, String where, String before, String after) {

        public Line(String sheet, int row, String text) {
            this(sheet, row, text, null, null, null);
        }
    }

    /** What reading a workbook found: the changes (planned, or made when {@code applied}) and the problems. */
    public record Result(boolean applied, List<Line> changes, List<Line> problems) {
    }

    /* ═══════════════════════════════════ the style rows ═══════════════════════════════════ */

    /** One 말투 row: its label, how to read it from the profile, and what the seller may write. */
    private enum StyleField {
        TONE("말투", "정중하게 · 친근하게 · 간결하게 중 하나"),
        LENGTH("길이", "짧게 · 보통 · 자세히 중 하나"),
        EMOJI("이모지", "사용하지 않음 · 가끔 한 개 중 하나"),
        ADDRESS("고객 호칭", "예: 고객님"),
        GREETING("첫 인사", "예: 안녕하세요, ○○입니다."),
        CLOSING("끝 인사", "예: 감사합니다. 불만이 담긴 후기에는 밝은 인사 대신 차분하게 끝맺습니다."),
        REQUIRED("꼭 넣을 표현", "한 줄에 하나. 사실(기간·금액·무료 등)을 약속하는 표현은 넣을 수 없습니다."),
        FORBIDDEN("절대 쓰지 않을 표현", "한 줄에 하나. 이 표현이 든 초안은 고친 뒤에만 승인할 수 있습니다."),
        AVOID("되도록 피할 표현", "한 줄에 하나, 「피할 말 → 대신 쓸 말」. 초안에 표시만 합니다."),
        FALLBACK("답을 모를 때 문구", "문의 답변에서 근거가 없을 때 쓰는 문장입니다.");

        final String label;
        final String help;

        StyleField(String label, String help) {
            this.label = label;
            this.help = help;
        }

        String valueOf(AnswerStyleProfile p) {
            return switch (this) {
                case TONE -> p.tone().labelKo();
                case LENGTH -> p.length().labelKo();
                case EMOJI -> p.emoji().labelKo();
                case ADDRESS -> nz(p.customerAddress());
                case GREETING -> nz(p.greeting());
                case CLOSING -> nz(p.closing());
                case REQUIRED -> String.join("\n", p.requiredPhrases());
                case FORBIDDEN -> String.join("\n", p.forbiddenPhrases());
                case AVOID -> String.join("\n", p.avoidPhrases().stream()
                        .map(a -> a.replacement() == null ? a.phrase() : a.phrase() + " → " + a.replacement()).toList());
                case FALLBACK -> nz(p.unknownFallback());
            };
        }

        static Optional<StyleField> of(String label) {
            for (StyleField f : values()) {
                if (f.label.equals(label)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }
    }

    /** The Korean names the template screen shows ({@code frontend/src/lib/reviewReplyTemplates.ts}). */
    static final Map<ReviewReplyTemplateKey, String[]> TEMPLATE_NAMES = new LinkedHashMap<>();

    static {
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.POSITIVE, new String[] {"별점 4~5점 기본 문구",
                "별점이 4~5점인 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.QUALITY, new String[] {"불량 · 파손 리뷰",
                "상품이 불량이거나 깨지고 고장 났다는 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.DELIVERY, new String[] {"배송 리뷰",
                "배송이나 발송이 늦었다는 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.PACKAGING, new String[] {"포장 리뷰",
                "포장이나 박스 상태를 지적한 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.PRODUCT_INFO, new String[] {"상품 설명과 다르다는 리뷰",
                "설명이나 사진과 실제가 다르다는 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.PRICING, new String[] {"가격 리뷰",
                "가격이 비싸다거나 가성비를 말한 리뷰에 씁니다."});
        TEMPLATE_NAMES.put(ReviewReplyTemplateKey.GENERAL, new String[] {"그 밖의 리뷰",
                "별점이 4점 미만이고 위 유형의 낱말도 없는 리뷰에 씁니다."});
    }

    /* ═══════════════════════════════════ download ═══════════════════════════════════ */

    /** The workbook as this org stands now. */
    public byte[] export(UUID orgId) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles look = new Styles(book);
            writeGuide(book, look);
            writeStyle(book, look, styles.profileFor(orgId));
            writeTemplates(book, look, orgId);
            writeManual(book, look, orgId);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("workbook could not be written", e);
        }
    }

    private static void writeGuide(Workbook book, Styles look) {
        Sheet sheet = book.createSheet(SHEET_GUIDE);
        String[] lines = {
                "이 파일로 답글의 말투 · 답변 템플릿 · 안내 매뉴얼을 한 번에 관리합니다.",
                "",
                "1. 고칠 줄의 「새 값」 · 「새 문구」 · 「안내 문장」을 고치고, 그 줄의 「상태」를 「사용」으로 바꿉니다.",
                "2. 「확인 전」인 줄은 답글에 쓰이지 않습니다. 안내 매뉴얼의 「확인 전」 줄은 「지식 → 확인 필요」에 제안으로 들어갑니다.",
                "3. 【확인: …】이 남아 있는 줄은 「사용」으로 등록할 수 없습니다. 사장님만 아는 내용으로 바꿔 주세요.",
                "4. 「사용 안 함」은 안내를 지우지 않고 끕니다. 나중에 「사용」으로 되돌릴 수 있습니다.",
                "5. 「고객은 이렇게 말해요」에는 고객이 그 문제를 말하는 표현을 한 줄에 하나씩 적습니다. "
                        + "리뷰에 이 표현이 있으면 그 안내가 답글 초안의 근거로 붙습니다. 「안 떨어져요」처럼 부정하는 말은 붙지 않습니다.",
                "6. 새 안내는 맨 아래 빈 줄에 적습니다. 관리번호는 비워 두세요. 상품 안내는 상품번호나 상품명이 있어야 합니다.",
                "7. 지식 화면의 「엑셀 올리기」로 올리면, 바뀌는 내용을 먼저 보여 드리고 「적용」을 눌러야 반영됩니다.",
                "",
                "관리번호 · 관리키 칸은 고치지 마세요. 어느 안내인지 찾는 데 씁니다."};
        for (int i = 0; i < lines.length; i++) {
            Cell cell = sheet.createRow(i).createCell(0);
            cell.setCellValue(lines[i]);
            cell.setCellStyle(i == 0 ? look.title : look.wrap);
        }
        sheet.setColumnWidth(0, 140 * 256);
    }

    private static void writeStyle(Workbook book, Styles look, AnswerStyleProfile profile) {
        Sheet sheet = header(book, look, SHEET_STYLE, STYLE_HEADERS, new int[] {18, 40, 40, 12, 60});
        int r = 1;
        for (StyleField field : StyleField.values()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, field.label, look.bold);
            cell(row, 1, field.valueOf(profile), look.wrap);
            cell(row, 2, "", look.edit);
            cell(row, 3, "", look.edit);
            cell(row, 4, field.help, look.note);
        }
        statusList(sheet, 3, 1, r - 1, USE, PENDING, DEFAULT);
    }

    private void writeTemplates(Workbook book, Styles look, UUID orgId) {
        Sheet sheet = header(book, look, SHEET_TEMPLATES, TEMPLATE_HEADERS, new int[] {22, 36, 60, 60, 12, 16});
        int r = 1;
        for (Map.Entry<ReviewReplyTemplateKey, String[]> entry : TEMPLATE_NAMES.entrySet()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, entry.getValue()[0], look.bold);
            cell(row, 1, entry.getValue()[1], look.note);
            cell(row, 2, templates.bodyFor(orgId, entry.getKey()), look.wrap);
            cell(row, 3, "", look.edit);
            cell(row, 4, "", look.edit);
            cell(row, 5, entry.getKey().category(), look.note);
        }
        statusList(sheet, 4, 1, r - 1, USE, PENDING, DEFAULT);
    }

    private void writeManual(Workbook book, Styles look, UUID orgId) {
        Sheet sheet = header(book, look, SHEET_MANUAL, MANUAL_HEADERS,
                new int[] {14, 10, 14, 28, 12, 30, 70, 30, 12, 10, 30, 10});
        // Not for people: what the row was when downloaded, so an old file cannot overwrite a newer screen.
        sheet.setColumnHidden(MANUAL_HEADERS.indexOf("변경 확인"), true);
        Map<UUID, Product> owned = new HashMap<>();
        products.findAllByOrgId(orgId).forEach(p -> owned.put(p.getId(), p));
        List<String[]> rows = new ArrayList<>();
        for (ProductKnowledgeSource s : productSources.findAllByOrgId(orgId)) {
            if (!personWritten(s.getAuthoredOrigin(), s.getDocumentName()) || s.getVariantId() != null) {
                continue;
            }
            Product product = owned.get(s.getProductId());
            rows.add(new String[] {"P-" + s.getId(), SCOPE_PRODUCT, refOf(orgId, s.getProductId()),
                    nameOf(product), typeLabel(s.getSourceType()), s.getTitle(), s.getBody(),
                    lines(s.getCustomerPhrases()), s.isActive() ? USE : OFF, "", "", fingerprint(s)});
        }
        for (OrgKnowledgeSource s : orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId)) {
            if (!personWritten(s.getAuthoredOrigin(), s.getDocumentName())) {
                continue;
            }
            rows.add(new String[] {"O-" + s.getId(), SCOPE_ORG, "", "", s.getKnowledgeType().labelKo(), s.getTitle(),
                    s.getBody(), lines(s.getCustomerPhrases()), s.isActive() ? USE : OFF, "", "", fingerprint(s)});
        }
        for (KnowledgeCandidate c : candidateRows.findAllByOrgIdAndStateOrderByEvidenceCountDescCreatedAtDesc(orgId,
                "OPEN")) {
            if (!KnowledgeCandidateService.ORIGIN_ISSUE_GUIDANCE.equals(c.getOrigin())
                    && !KnowledgeCandidateService.ORIGIN_REPEATED_ANSWER.equals(c.getOrigin())) {
                continue;
            }
            boolean product = "PRODUCT".equals(c.getScope()) && c.getProductId() != null;
            rows.add(new String[] {"C-" + c.getId(), product ? SCOPE_PRODUCT : SCOPE_ORG,
                    product ? refOf(orgId, c.getProductId()) : "", product ? nameOf(owned.get(c.getProductId())) : "",
                    "", c.getSubject(), c.getContent(), lines(c.getCustomerPhrases()), PENDING,
                    String.valueOf(c.getEvidenceCount()),
                    KnowledgeBlanks.carries(c.getContent()) ? "【확인】을 채워야 사용할 수 있습니다" : "", fingerprint(c)});
        }
        rows.sort(Comparator.<String[], Integer>comparing(row -> SCOPE_PRODUCT.equals(row[1]) ? 0 : 1)
                .thenComparing(row -> row[3])
                .thenComparing(row -> row[0].startsWith("C-") ? 1 : 0)
                .thenComparing(row -> row[5]));
        int r = 1;
        for (String[] values : rows) {
            Row row = sheet.createRow(r++);
            boolean tooLong = values[6].length() > MAX_CELL;
            for (int c = 0; c < values.length; c++) {
                String value = values[c];
                if (c == 6 && tooLong) {
                    value = "(본문이 길어 엑셀에 싣지 않았습니다. 지식 화면에서 고쳐 주세요.)";
                } else if (c == 8 && tooLong) {
                    value = SCREEN;
                }
                cell(row, c, value, c == 0 || c == 9 ? look.note
                        : c == 6 || c == 7 ? look.wrap : c == 8 ? look.edit : look.plain);
            }
        }
        // Thirty open rows for new guidance, already formatted so a new row looks like the rest.
        for (int i = 0; i < 30; i++) {
            Row row = sheet.createRow(r++);
            for (int c = 0; c < MANUAL_HEADERS.size(); c++) {
                cell(row, c, "", c == 6 || c == 7 ? look.wrap : c == 8 ? look.edit : look.plain);
            }
        }
        statusList(sheet, 8, 1, r - 1, USE, PENDING, OFF);
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation scope = helper.createValidation(helper.createExplicitListConstraint(
                new String[] {SCOPE_PRODUCT, SCOPE_ORG}), new CellRangeAddressList(1, r - 1, 1, 1));
        sheet.addValidationData(scope);
    }

    /* ═══════════════════════════════════ upload ═══════════════════════════════════ */

    /**
     * Read an uploaded workbook. With {@code apply} false nothing is written and the result says what would change;
     * with it true each change goes through its ordinary write path, and one that is refused is a problem on its row.
     */
    public Result read(UUID orgId, InputStream file, boolean apply, UUID actorUserId, String actorName) {
        Workbook book;
        try {
            book = WorkbookFactory.create(file);
        } catch (IOException | RuntimeException e) {
            throw ApiException.badRequest("엑셀 파일을 읽지 못했습니다. 지식 화면에서 내려받은 .xlsx 파일을 올려 주세요.");
        }
        Plan plan = new Plan();
        try (book) {
            Sheet style = book.getSheet(SHEET_STYLE);
            Sheet templateSheet = book.getSheet(SHEET_TEMPLATES);
            Sheet manual = book.getSheet(SHEET_MANUAL);
            if (style == null && templateSheet == null && manual == null) {
                throw ApiException.badRequest("「말투」 · 「답변 템플릿」 · 「안내 매뉴얼」 시트가 없습니다. 지식 화면에서 내려받은 파일을 써 주세요.");
            }
            if (style != null) {
                readStyle(orgId, style, plan, actorUserId);
            }
            if (templateSheet != null) {
                readTemplates(orgId, templateSheet, plan, actorUserId);
            }
            if (manual != null) {
                readManual(orgId, manual, plan, actorUserId, actorName);
            }
        } catch (IOException e) {
            throw ApiException.badRequest("엑셀 파일을 읽지 못했습니다.");
        }
        if (!apply) {
            return new Result(false, plan.changes(), List.copyOf(plan.problems));
        }
        beforeApply.run();
        List<Line> done = new ArrayList<>();
        for (Step step : plan.steps) {
            try {
                step.action.run();
                done.add(step.line);
            } catch (ApiException e) {
                plan.problems.add(new Line(step.line.sheet(), step.line.row(), e.getMessage()));
            }
        }
        return new Result(true, List.copyOf(done), List.copyOf(plan.problems));
    }

    /** Runs between planning and the first write — where a test puts the screen save that 적용 must not overwrite. */
    private Runnable beforeApply = () -> { };

    void setBeforeApply(Runnable beforeApply) {
        this.beforeApply = beforeApply;
    }

    private record Step(Line line, Runnable action) {
    }

    private static final class Plan {
        final List<Step> steps = new ArrayList<>();
        final List<Line> problems = new ArrayList<>();

        void change(String sheet, int row, String text, Runnable action) {
            steps.add(new Step(new Line(sheet, row, text), action));
        }

        void change(String sheet, int row, String text, String where, String before, String after, Runnable action) {
            steps.add(new Step(new Line(sheet, row, text, where, before, after), action));
        }

        /**
         * The change — or, when {@code conflict} names one, the conflict with what the row says now. The change checks
         * again when it runs, since a screen can save between this read and 적용: {@code unchanged} false then refuses it
         * on its row.
         */
        void guarded(String conflict, BooleanSupplier unchanged, String sheet, int row, String text, String where,
                     String before, String after, Runnable action) {
            if (conflict != null) {
                problems.add(new Line(sheet, row, text + " — " + conflict, where, before, null));
                return;
            }
            change(sheet, row, text, where, before, after, () -> {
                if (!unchanged.getAsBoolean()) {
                    throw ApiException.conflict(text + " — " + STALE);
                }
                action.run();
            });
        }

        void problem(String sheet, int row, String text) {
            problems.add(new Line(sheet, row, text));
        }

        List<Line> changes() {
            return steps.stream().map(Step::line).toList();
        }
    }

    private void readStyle(UUID orgId, Sheet sheet, Plan plan, UUID actorUserId) {
        Map<String, Integer> col = columns(sheet, STYLE_HEADERS);
        if (col == null) {
            plan.problem(SHEET_STYLE, 1, "머리줄(항목 · 새 값 · 상태)이 없어 이 시트를 읽지 않았습니다.");
            return;
        }
        AnswerStyleProfile now = styles.profileFor(orgId);
        AnswerTone tone = now.tone();
        AnswerLength length = now.length();
        EmojiPolicy emoji = now.emoji();
        String address = now.customerAddress();
        String greeting = now.greeting();
        String closing = now.closing();
        String fallback = now.unknownFallback();
        List<String> required = now.requiredPhrases();
        List<String> forbidden = now.forbiddenPhrases();
        List<AvoidPhrase> avoid = now.avoidPhrases();
        List<String> said = new ArrayList<>();
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        int firstRow = 0;
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String label = text(row, col.get("항목"));
            String status = status(text(row, col.get("상태")));
            String value = text(row, col.get("새 값"));
            String seen = text(row, col.get("지금 값"));
            if (label.isEmpty() || status.isEmpty() || PENDING.equals(status)) {
                continue;
            }
            Optional<StyleField> field = StyleField.of(label);
            if (field.isEmpty()) {
                plan.problem(SHEET_STYLE, r + 1, "「" + label + "」은 알 수 없는 항목입니다.");
                continue;
            }
            if (!USE.equals(status) && !DEFAULT.equals(status)) {
                plan.problem(SHEET_STYLE, r + 1, "상태는 사용 · 확인 전 · 기본값으로 중 하나로 적어 주세요.");
                continue;
            }
            boolean reset = DEFAULT.equals(status);
            if (!reset && value.isEmpty()) {
                plan.problem(SHEET_STYLE, r + 1, "「새 값」이 비어 있습니다. 비우려면 상태를 「기본값으로」로 적어 주세요.");
                continue;
            }
            if (!reset && KnowledgeBlanks.carries(value)) {
                plan.problem(SHEET_STYLE, r + 1, "【확인: …】을 채운 뒤 「사용」으로 적어 주세요.");
                continue;
            }
            String current = field.get().valueOf(now).strip();
            if (!reset && value.equals(current)) {
                continue;
            }
            // 「지금 값」 is what the row said when it was downloaded; a different value now is someone else's newer save.
            if (col.containsKey("지금 값") && !seen.equals(current)) {
                plan.problems.add(new Line(SHEET_STYLE, r + 1, "「" + label + "」 — " + STALE, "말투", current, null));
                continue;
            }
            switch (field.get()) {
                case TONE -> {
                    AnswerTone t = reset ? AnswerTone.POLITE : byLabel(AnswerTone.values(), AnswerTone::labelKo, value);
                    if (t == null) {
                        plan.problem(SHEET_STYLE, r + 1, field.get().help + "로 적어 주세요.");
                        continue;
                    }
                    tone = t;
                }
                case LENGTH -> {
                    AnswerLength l = reset ? AnswerLength.NORMAL
                            : byLabel(AnswerLength.values(), AnswerLength::labelKo, value);
                    if (l == null) {
                        plan.problem(SHEET_STYLE, r + 1, field.get().help + "로 적어 주세요.");
                        continue;
                    }
                    length = l;
                }
                case EMOJI -> {
                    EmojiPolicy e = reset ? EmojiPolicy.NONE : byLabel(EmojiPolicy.values(), EmojiPolicy::labelKo, value);
                    if (e == null) {
                        plan.problem(SHEET_STYLE, r + 1, field.get().help + "로 적어 주세요.");
                        continue;
                    }
                    emoji = e;
                }
                case ADDRESS -> address = reset ? null : value;
                case GREETING -> greeting = reset ? null : value;
                case CLOSING -> closing = reset ? null : value;
                case FALLBACK -> fallback = reset ? null : value;
                case REQUIRED -> required = reset ? List.of() : splitLines(value);
                case FORBIDDEN -> forbidden = reset ? List.of() : splitLines(value);
                case AVOID -> avoid = reset ? List.of() : splitLines(value).stream().map(KnowledgeWorkbookService::avoidOf)
                        .toList();
            }
            said.add(label);
            before.add(label + ": " + (current.isEmpty() ? "(없음)" : current));
            after.add(label + ": " + (reset ? "(기본값)" : value));
            if (firstRow == 0) {
                firstRow = r + 1;
            }
        }
        if (said.isEmpty()) {
            return;
        }
        AnswerStyleRequest request = new AnswerStyleRequest(tone, length, emoji, greeting, closing, address, required,
                forbidden, fallback, avoid);
        AnswerStyleRequest asSaved = new AnswerStyleRequest(now.tone(), now.length(), now.emoji(), now.greeting(),
                now.closing(), now.customerAddress(), now.requiredPhrases(), now.forbiddenPhrases(),
                now.unknownFallback(), now.avoidPhrases());
        if (request.equals(asSaved)) {
            return;
        }
        plan.change(SHEET_STYLE, firstRow, "말투 바꿈 — " + String.join(" · ", said), "문의 답변 · 리뷰 답글",
                String.join("\n", before), String.join("\n", after), () -> styles.save(orgId, request, actorUserId));
    }

    private void readTemplates(UUID orgId, Sheet sheet, Plan plan, UUID actorUserId) {
        Map<String, Integer> col = columns(sheet, TEMPLATE_HEADERS);
        if (col == null) {
            plan.problem(SHEET_TEMPLATES, 1, "머리줄(유형 · 새 문구 · 상태)이 없어 이 시트를 읽지 않았습니다.");
            return;
        }
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String status = status(text(row, col.get("상태")));
            if (status.isEmpty() || PENDING.equals(status)) {
                continue;
            }
            String name = text(row, col.get("유형"));
            Optional<ReviewReplyTemplateKey> key = ReviewReplyTemplateKey.of(text(row, col.get("관리키")))
                    .or(() -> TEMPLATE_NAMES.entrySet().stream().filter(e -> e.getValue()[0].equals(name))
                            .map(Map.Entry::getKey).findFirst());
            if (key.isEmpty()) {
                plan.problem(SHEET_TEMPLATES, r + 1, "어느 유형인지 알 수 없습니다. 「유형」과 「관리키」를 고치지 말아 주세요.");
                continue;
            }
            String label = TEMPLATE_NAMES.get(key.get())[0];
            String where = TEMPLATE_NAMES.get(key.get())[1];
            String current = templates.bodyFor(orgId, key.get()).strip();
            String conflict = !col.containsKey("지금 문구") ? NO_BASE
                    : text(row, col.get("지금 문구")).equals(current) ? null : STALE;
            BooleanSupplier unchanged = () -> templates.bodyFor(orgId, key.get()).strip().equals(current);
            if (DEFAULT.equals(status)) {
                if (templates.isCustomized(orgId, key.get())) {
                    plan.guarded(conflict, unchanged, SHEET_TEMPLATES, r + 1, "「" + label + "」 기본 문구로 되돌림", where,
                            current, "(기본 문구)", () -> templates.reset(orgId, key.get().category()));
                }
                continue;
            }
            if (!USE.equals(status)) {
                plan.problem(SHEET_TEMPLATES, r + 1, "상태는 사용 · 확인 전 · 기본값으로 중 하나로 적어 주세요.");
                continue;
            }
            String body = text(row, col.get("새 문구"));
            if (body.isEmpty()) {
                plan.problem(SHEET_TEMPLATES, r + 1, "「새 문구」가 비어 있습니다.");
                continue;
            }
            if (KnowledgeBlanks.carries(body)) {
                plan.problem(SHEET_TEMPLATES, r + 1, "「" + label + "」 — 【확인: …】을 채운 뒤 「사용」으로 적어 주세요.");
                continue;
            }
            if (body.equals(current)) {
                continue;
            }
            plan.guarded(conflict, unchanged, SHEET_TEMPLATES, r + 1, "「" + label + "」 문구 바꿈", where, current, body,
                    () -> templates.save(orgId, key.get().category(), body, actorUserId));
        }
    }

    private void readManual(UUID orgId, Sheet sheet, Plan plan, UUID actorUserId, String actorName) {
        Map<String, Integer> col = columns(sheet, MANUAL_HEADERS);
        if (col == null) {
            plan.problem(SHEET_MANUAL, 1, "머리줄(관리번호 · 주제 · 안내 문장 · 상태 …)이 없어 이 시트를 읽지 않았습니다.");
            return;
        }
        Map<UUID, Product> owned = new HashMap<>();
        products.findAllByOrgId(orgId).forEach(p -> owned.put(p.getId(), p));
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            String id = text(row, col.get("관리번호"));
            String subject = text(row, col.get("주제"));
            String body = text(row, col.get("안내 문장"));
            String rawPhrases = text(row, col.get("고객은 이렇게 말해요"));
            List<String> phrases = CustomerPhrases.parse(rawPhrases);
            String rawStatus = text(row, col.get("상태"));
            String status = status(rawStatus);
            String base = text(row, col.get("변경 확인"));
            int at = r + 1;
            if (id.isEmpty() && subject.isEmpty() && body.isEmpty()) {
                continue;
            }
            if (SCREEN.equals(status)) {
                continue;
            }
            if (!status.isEmpty() && !USE.equals(status) && !PENDING.equals(status) && !OFF.equals(status)) {
                plan.problem(SHEET_MANUAL, at, "상태는 사용 · 확인 전 · 사용 안 함 중 하나로 적어 주세요.");
                continue;
            }
            String label = "「" + (subject.isEmpty() ? "제목 없음" : subject) + "」";
            if (CustomerPhrases.overflows(rawPhrases)) {
                plan.problem(SHEET_MANUAL, at, "고객 표현은 " + CustomerPhrases.MAX_PHRASES + "개까지만 저장됩니다. 앞의 "
                        + CustomerPhrases.MAX_PHRASES + "개만 씁니다.");
            }
            if (id.startsWith("P-") || id.startsWith("O-") || id.startsWith("C-")) {
                UUID ref = uuid(id.substring(2));
                if (ref == null) {
                    plan.problem(SHEET_MANUAL, at, "관리번호를 읽지 못했습니다. 새 안내라면 관리번호를 비워 주세요.");
                    continue;
                }
                if (id.startsWith("P-")) {
                    existingProduct(orgId, ref, subject, body, phrases, status, at, label, base, plan);
                } else if (id.startsWith("O-")) {
                    existingOrg(orgId, ref, subject, body, phrases, status, at, label, base, plan);
                } else {
                    existingCandidate(orgId, ref, subject, body, phrases, status, at, label, base, plan, actorUserId,
                            actorName, text(row, col.get("종류")));
                }
                continue;
            }
            if (!id.isEmpty()) {
                plan.problem(SHEET_MANUAL, at, "관리번호를 읽지 못했습니다. 새 안내라면 관리번호를 비워 주세요.");
                continue;
            }
            newRow(orgId, owned, row, col, subject, body, phrases, status, at, label, plan, actorUserId, actorName);
        }
    }

    private void existingProduct(UUID orgId, UUID id, String subject, String body, List<String> phrases,
                                 String status, int at, String label, String base, Plan plan) {
        Optional<ProductKnowledgeSource> found = productSources.findByIdAndOrgId(id, orgId);
        if (found.isEmpty()) {
            plan.problem(SHEET_MANUAL, at, "관리번호에 맞는 안내가 없습니다. 새 안내라면 관리번호를 비워 주세요.");
            return;
        }
        ProductKnowledgeSource s = found.get();
        String seen = fingerprint(s);
        String conflict = base.isEmpty() ? NO_BASE : base.equals(seen) ? null : STALE;
        BooleanSupplier unchanged = () -> productSources.findByIdAndOrgId(id, orgId)
                .map(KnowledgeWorkbookService::fingerprint).orElse("").equals(seen);
        String where = SCOPE_PRODUCT + " · " + nameOf(products.findById(s.getProductId()).orElse(null));
        String was = shown(s.getBody(), s.getCustomerPhrases());
        if (OFF.equals(status)) {
            if (s.isActive()) {
                plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + " 안내를 끔", where, was, "(쓰지 않음)", () -> {
                    s.setActive(false);
                    productSources.save(s);
                });
            }
            return;
        }
        if (!USE.equals(status)) {
            if (PENDING.equals(status)) {
                plan.problem(SHEET_MANUAL, at, "이미 등록된 안내입니다. 「사용」이나 「사용 안 함」으로 적어 주세요.");
            }
            return;
        }
        if (body.isEmpty() || KnowledgeBlanks.carries(body)) {
            plan.problem(SHEET_MANUAL, at, label + " — " + (body.isEmpty() ? "「안내 문장」이 비어 있습니다."
                    : "【확인: …】이 남아 있어 「사용」으로 등록할 수 없습니다."));
            return;
        }
        String title = subject.isEmpty() ? s.getTitle() : subject;
        boolean same = s.isActive() && title.equals(s.getTitle()) && body.equals(s.getBody())
                && Objects.equals(CustomerPhrases.store(phrases), CustomerPhrases.normalized(s.getCustomerPhrases()));
        if (same) {
            return;
        }
        plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + (s.isActive() ? " 안내 고침" : " 안내를 다시 켜고 고침"),
                where, was, shown(body, CustomerPhrases.store(phrases)), () -> {
            productLibrary.update(orgId, s.getId(), new KnowledgeSourceRequest(s.getSourceType(), title, body,
                    s.getSourceUrl(), s.getVariantId(), phrases));
            if (!s.isActive()) {
                productSources.findByIdAndOrgId(s.getId(), orgId).ifPresent(fresh -> {
                    fresh.setActive(true);
                    productSources.save(fresh);
                });
            }
        });
    }

    private void existingOrg(UUID orgId, UUID id, String subject, String body, List<String> phrases, String status,
                             int at, String label, String base, Plan plan) {
        Optional<OrgKnowledgeSource> found = orgSources.findByIdAndOrgId(id, orgId);
        if (found.isEmpty()) {
            plan.problem(SHEET_MANUAL, at, "관리번호에 맞는 안내가 없습니다. 새 안내라면 관리번호를 비워 주세요.");
            return;
        }
        OrgKnowledgeSource s = found.get();
        String seen = fingerprint(s);
        String conflict = base.isEmpty() ? NO_BASE : base.equals(seen) ? null : STALE;
        BooleanSupplier unchanged = () -> orgSources.findByIdAndOrgId(id, orgId)
                .map(KnowledgeWorkbookService::fingerprint).orElse("").equals(seen);
        String was = shown(s.getBody(), s.getCustomerPhrases());
        if (OFF.equals(status)) {
            if (s.isActive()) {
                plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + " 안내를 끔", SCOPE_ORG, was, "(쓰지 않음)", () -> {
                    s.setActive(false);
                    orgSources.save(s);
                });
            }
            return;
        }
        if (!USE.equals(status)) {
            if (PENDING.equals(status)) {
                plan.problem(SHEET_MANUAL, at, "이미 등록된 안내입니다. 「사용」이나 「사용 안 함」으로 적어 주세요.");
            }
            return;
        }
        if (body.isEmpty() || KnowledgeBlanks.carries(body)) {
            plan.problem(SHEET_MANUAL, at, label + " — " + (body.isEmpty() ? "「안내 문장」이 비어 있습니다."
                    : "【확인: …】이 남아 있어 「사용」으로 등록할 수 없습니다."));
            return;
        }
        String title = subject.isEmpty() ? s.getTitle() : subject;
        boolean same = s.isActive() && title.equals(s.getTitle()) && body.equals(s.getBody())
                && Objects.equals(CustomerPhrases.store(phrases), CustomerPhrases.normalized(s.getCustomerPhrases()));
        if (same) {
            return;
        }
        plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + (s.isActive() ? " 안내 고침" : " 안내를 다시 켜고 고침"),
                SCOPE_ORG, was, shown(body, CustomerPhrases.store(phrases)), () -> {
            orgKnowledge.update(orgId, s.getId(), new OrgKnowledgeRequest(s.getKnowledgeType(), title, body,
                    s.getSourceUrl(), phrases));
            if (!s.isActive()) {
                orgSources.findByIdAndOrgId(s.getId(), orgId).ifPresent(fresh -> {
                    fresh.setActive(true);
                    orgSources.save(fresh);
                });
            }
        });
    }

    private void existingCandidate(UUID orgId, UUID id, String subject, String body, List<String> phrases,
                                   String status, int at, String label, String base, Plan plan, UUID actorUserId,
                                   String actorName, String kind) {
        Optional<KnowledgeCandidate> found = candidateRows.findByIdAndOrgId(id, orgId);
        if (found.isEmpty()) {
            plan.problem(SHEET_MANUAL, at, "관리번호에 맞는 제안이 없습니다. 이 회사에서 내려받은 파일인지 확인해 주세요.");
            return;
        }
        if (!"OPEN".equals(found.get().getState())) {
            plan.problem(SHEET_MANUAL, at, "이미 처리한 제안입니다. 새로 내려받은 파일로 다시 해 주세요.");
            return;
        }
        KnowledgeCandidate c = found.get();
        boolean product = "PRODUCT".equals(c.getScope());
        String seen = fingerprint(c);
        String conflict = base.isEmpty() ? NO_BASE : base.equals(seen) ? null : STALE;
        BooleanSupplier unchanged = () -> candidateRows.findByIdAndOrgId(id, orgId)
                .map(KnowledgeWorkbookService::fingerprint).orElse("").equals(seen);
        String where = product && c.getProductId() != null
                ? SCOPE_PRODUCT + " · " + nameOf(products.findById(c.getProductId()).orElse(null)) : SCOPE_ORG;
        String was = shown(c.getContent(), c.getCustomerPhrases());
        String now = shown(body.isEmpty() ? c.getContent() : body, CustomerPhrases.store(phrases));
        if (OFF.equals(status)) {
            plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + " 제안을 보류", where, was, "(보류)",
                    () -> candidateService.dismiss(orgId, id, actorName));
            return;
        }
        if (USE.equals(status)) {
            if (body.isEmpty() || KnowledgeBlanks.carries(body)) {
                plan.problem(SHEET_MANUAL, at, label + " — " + (body.isEmpty() ? "「안내 문장」이 비어 있습니다."
                        : "【확인: …】이 남아 있어 「사용」으로 등록할 수 없습니다. 사장님만 아는 내용으로 바꿔 주세요."));
                return;
            }
            KnowledgeSourceType productType = product ? sourceType(kind) : null;
            OrgKnowledgeType orgType = product ? null : orgType(kind);
            plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + " 제안을 안내로 등록", where, was, now,
                    () -> candidateService.accept(orgId, id,
                    subject.isEmpty() ? null : subject, body, productType, orgType, null, phrases, actorUserId,
                    actorName));
            return;
        }
        boolean edited = (!body.isEmpty() && !body.equals(c.getContent()))
                || !Objects.equals(CustomerPhrases.store(phrases), CustomerPhrases.normalized(c.getCustomerPhrases()));
        if (edited) {
            plan.guarded(conflict, unchanged, SHEET_MANUAL, at, label + " 제안 고침 (확인 전)", where, was, now,
                    () -> candidateService.revise(orgId, id, body, phrases));
        }
    }

    private void newRow(UUID orgId, Map<UUID, Product> owned, Row row, Map<String, Integer> col, String subject,
                        String body, List<String> phrases, String status, int at, String label, Plan plan,
                        UUID actorUserId, String actorName) {
        if (subject.isEmpty() || body.isEmpty()) {
            plan.problem(SHEET_MANUAL, at, "새 안내는 「주제」와 「안내 문장」이 모두 있어야 합니다.");
            return;
        }
        if (OFF.equals(status)) {
            return;
        }
        boolean org = text(row, col.get("범위")).replace(" ", "").equals(SCOPE_ORG.replace(" ", ""));
        String kind = text(row, col.get("종류"));
        UUID productId = null;
        if (!org) {
            productId = productOf(orgId, owned, text(row, col.get("상품번호")), text(row, col.get("상품명")));
            if (productId == null) {
                plan.problem(SHEET_MANUAL, at, "상품을 찾지 못했습니다. 상품번호나 상품명을 확인하시거나, 회사 전체 안내라면 범위를 「회사 전체」로 적어 주세요.");
                return;
            }
        }
        UUID target = productId;
        boolean blank = KnowledgeBlanks.carries(body);
        // A new row whose 주제 a guidance already has is not quietly turned into an edit of it: an edit needs the row's
        // 관리번호 and what it said when downloaded, and this row has neither (2026-10-27 second review).
        Optional<String> taken = target == null
                ? orgSources.findAllByOrgIdOrderByCreatedAtAsc(orgId).stream()
                        .filter(s -> s.getTitle().equals(subject) && personWritten(s.getAuthoredOrigin(),
                                s.getDocumentName())).map(s -> "O-" + s.getId()).findFirst()
                : productSources.findAllByOrgIdAndProductIdOrderByCreatedAtAsc(orgId, target).stream()
                        .filter(s -> s.getTitle().equals(subject) && s.getVariantId() == null
                                && personWritten(s.getAuthoredOrigin(), s.getDocumentName()))
                        .map(s -> "P-" + s.getId()).findFirst();
        if (taken.isPresent()) {
            plan.problem(SHEET_MANUAL, at, label + " — 같은 주제의 안내가 이미 있습니다(관리번호 " + taken.get()
                    + "). 그 줄을 고치시거나, 다른 안내라면 주제를 바꿔 주세요.");
            return;
        }
        if (USE.equals(status) && !blank) {
            plan.change(SHEET_MANUAL, at, label + " 새 안내 등록", target == null ? SCOPE_ORG
                    : SCOPE_PRODUCT + " · " + nameOf(owned.get(target)), null,
                    shown(body, CustomerPhrases.store(phrases)), () -> {
                if (target != null) {
                    productLibrary.create(orgId, target, new KnowledgeSourceRequest(sourceType(kind), subject, body,
                            null, null, phrases), actorUserId, actorName);
                } else {
                    orgKnowledge.create(orgId, new OrgKnowledgeRequest(orgType(kind), subject, body, null, phrases),
                            actorUserId, actorName);
                }
            });
            return;
        }
        if (USE.equals(status)) {
            plan.problem(SHEET_MANUAL, at, label + " — 【확인: …】이 남아 있어 「사용」으로 등록하지 않고 확인 필요에 제안으로 넣습니다.");
        }
        int evidence = number(text(row, col.get("같은 문제 리뷰")));
        plan.change(SHEET_MANUAL, at, label + " 확인 필요에 제안으로 넣음 (확인 전)", target == null ? SCOPE_ORG
                        : SCOPE_PRODUCT + " · " + nameOf(owned.get(target)), null,
                shown(body, CustomerPhrases.store(phrases)),
                () -> candidateService.suggestGuidance(orgId, target, subject, body, evidence, phrases));
    }

    /* ═══════════════════════════════════ helpers ═══════════════════════════════════ */

    /** Typed by a person on a screen — not an uploaded file, not read from a channel or a picture. */
    private static boolean personWritten(KnowledgeAuthorship origin, String documentName) {
        return (origin == null || origin == KnowledgeAuthorship.SELLER_ENTERED_KNOWLEDGE) && documentName == null;
    }

    private UUID productOf(UUID orgId, Map<UUID, Product> owned, String ref, String name) {
        if (!ref.isEmpty()) {
            for (ChannelProduct listing : listings.findAllByOrgIdAndExternalProductId(orgId, ref)) {
                if (listing.getProductId() != null && owned.containsKey(listing.getProductId())) {
                    return listing.getProductId();
                }
            }
        }
        if (!name.isEmpty()) {
            List<UUID> named = owned.values().stream().filter(p -> name.equals(nameOf(p))).map(Product::getId).toList();
            if (named.size() == 1) {
                return named.get(0);
            }
        }
        return null;
    }

    private String refOf(UUID orgId, UUID productId) {
        if (productId == null) {
            return "";
        }
        return listings.findByOrgIdAndProductId(orgId, productId).stream()
                .map(ChannelProduct::getExternalProductId).filter(Objects::nonNull).sorted().findFirst().orElse("");
    }

    private static String nameOf(Product product) {
        if (product == null) {
            return "";
        }
        String shown = OperatorProductName.displayNameOrNull(product);
        return shown == null ? nz(product.getName()) : shown;
    }

    private static final Map<KnowledgeSourceType, String> TYPE_LABELS = Map.of(
            KnowledgeSourceType.DESCRIPTION, "상품 설명", KnowledgeSourceType.FAQ, "자주 묻는 질문",
            KnowledgeSourceType.USAGE, "사용법", KnowledgeSourceType.POLICY, "정책", KnowledgeSourceType.LINK, "링크");

    private static String typeLabel(KnowledgeSourceType type) {
        return type == null ? "" : TYPE_LABELS.getOrDefault(type, "");
    }

    private static KnowledgeSourceType sourceType(String label) {
        return TYPE_LABELS.entrySet().stream().filter(e -> e.getValue().equals(label)).map(Map.Entry::getKey)
                .findFirst().orElse(KnowledgeSourceType.FAQ);
    }

    private static OrgKnowledgeType orgType(String label) {
        for (OrgKnowledgeType type : OrgKnowledgeType.values()) {
            if (type.labelKo().replace(" ", "").equals(label.replace(" ", ""))) {
                return type;
            }
        }
        return OrgKnowledgeType.GENERAL_CS_FAQ;
    }

    private static <T> T byLabel(T[] values, Function<T, String> label, String value) {
        for (T v : values) {
            if (label.apply(v).equals(value.strip())) {
                return v;
            }
        }
        return null;
    }

    private static AvoidPhrase avoidOf(String line) {
        String[] parts = line.split("→|->", 2);
        String phrase = parts[0].strip();
        String instead = parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : null;
        return new AvoidPhrase(phrase, instead);
    }

    private static List<String> splitLines(String value) {
        return value.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String lines(String stored) {
        return String.join("\n", CustomerPhrases.parse(stored));
    }

    /** A 상태 as typed, with the spacing people actually use: 「사용안함」, 「확인전」, 「기본값」. */
    private static String status(String raw) {
        String s = raw.replace(" ", "");
        return switch (s) {
            case "" -> "";
            case "사용" -> USE;
            case "확인전" -> PENDING;
            case "사용안함", "끄기" -> OFF;
            case "기본값으로", "기본값" -> DEFAULT;
            case "화면에서관리" -> SCREEN;
            default -> raw;
        };
    }

    private static int number(String raw) {
        try {
            return raw.isEmpty() ? 0 : Math.max(0, (int) Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A guidance as the preview shows it: its sentence, and the customer phrases under it when it has any. */
    private static String shown(String body, String storedPhrases) {
        List<String> phrases = CustomerPhrases.parse(storedPhrases);
        return phrases.isEmpty() ? nz(body)
                : nz(body) + "\n고객은 이렇게 말해요: " + String.join(", ", phrases);
    }

    /** What a row was when it was downloaded — written to 「변경 확인」 and compared on upload. */
    static String fingerprint(ProductKnowledgeSource s) {
        return digest(s.getTitle(), s.getBody(), CustomerPhrases.normalized(s.getCustomerPhrases()),
                String.valueOf(s.isActive()));
    }

    static String fingerprint(OrgKnowledgeSource s) {
        return digest(s.getTitle(), s.getBody(), CustomerPhrases.normalized(s.getCustomerPhrases()),
                String.valueOf(s.isActive()));
    }

    static String fingerprint(KnowledgeCandidate c) {
        return digest(c.getSubject(), c.getContent(), CustomerPhrases.normalized(c.getCustomerPhrases()), c.getState());
    }

    private static String digest(String... fields) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = nz(field).getBytes(StandardCharsets.UTF_8);
                sha.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static final DataFormatter FORMAT = new DataFormatter();

    private static String text(Row row, Integer column) {
        if (row == null || column == null) {
            return "";
        }
        Cell cell = row.getCell(column);
        return cell == null ? "" : FORMAT.formatCellValue(cell).replace("\r\n", "\n").strip();
    }

    /** Header name → column, or null when a header the sheet needs is missing. */
    private static Map<String, Integer> columns(Sheet sheet, List<String> expected) {
        Row head = sheet.getRow(0);
        if (head == null) {
            return null;
        }
        Map<String, Integer> found = new HashMap<>();
        for (Cell cell : head) {
            found.put(FORMAT.formatCellValue(cell).strip(), cell.getColumnIndex());
        }
        Map<String, Integer> out = new HashMap<>();
        for (String name : expected) {
            if (found.containsKey(name)) {
                out.put(name, found.get(name));
            }
        }
        // 설명 · 언제 쓰나요 · 메모 and the like are for people; the columns a row is read by must be there.
        List<String> needed = expected.stream().filter(h -> List.of("항목", "새 값", "상태", "유형", "새 문구", "관리키",
                "관리번호", "주제", "안내 문장").contains(h)).toList();
        return out.keySet().containsAll(needed) ? out : null;
    }

    private static Sheet header(Workbook book, Styles look, String name, List<String> headers, int[] widths) {
        Sheet sheet = book.createSheet(name);
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            cell(row, i, headers.get(i), look.head);
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    private static void statusList(Sheet sheet, int column, int first, int last, String... values) {
        if (last < first) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation validation = helper.createValidation(helper.createExplicitListConstraint(values),
                new CellRangeAddressList(first, last, column, column));
        validation.setShowErrorBox(false);
        sheet.addValidationData(validation);
    }

    private static void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /** The few looks the workbook uses — head, editable, note — so the cells to fill stand out. */
    private static final class Styles {
        final CellStyle head;
        final CellStyle title;
        final CellStyle plain;
        final CellStyle wrap;
        final CellStyle bold;
        final CellStyle note;
        final CellStyle edit;

        Styles(Workbook book) {
            Font boldFont = book.createFont();
            boldFont.setBold(true);
            Font grey = book.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            Font big = book.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            head = base(book);
            head.setFont(boldFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title = base(book);
            title.setFont(big);
            plain = base(book);
            wrap = base(book);
            wrap.setWrapText(true);
            bold = base(book);
            bold.setFont(boldFont);
            note = base(book);
            note.setFont(grey);
            note.setWrapText(true);
            edit = base(book);
            edit.setWrapText(true);
            edit.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            edit.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private static CellStyle base(Workbook book) {
            CellStyle style = book.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setBorderBottom(BorderStyle.HAIR);
            return style;
        }
    }
}
