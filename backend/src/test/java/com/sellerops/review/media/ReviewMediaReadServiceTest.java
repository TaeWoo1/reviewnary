package com.sellerops.review.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.common.ApiException;
import com.sellerops.product.detail.image.DetailImageFetcher;
import com.sellerops.product.detail.image.FetchedImage;
import com.sellerops.review.Review;
import com.sellerops.review.ReviewRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * <b>리뷰 화면의 사진 — 케이스 화면과 같은 뜻, 같은 문장, 같은 바이트 경로.</b>
 *
 * <p>조직은 principal에서만 온다. 다른 조직의 리뷰는 목록도 사진도 404이고, 그때는 fetch가 일어나지도 않는다.
 */
class ReviewMediaReadServiceTest {

    private final UUID org = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private ReviewRepository reviews;
    private ReviewMediaRepository media;
    private ReviewMediaInspector inspector;
    private ReviewMediaReadService service;
    private ReviewMediaController controller;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewRepository.class);
        media = mock(ReviewMediaRepository.class);
        inspector = mock(ReviewMediaInspector.class);
        service = new ReviewMediaReadService(reviews, media, inspector);
        controller = new ReviewMediaController(service);
        when(reviews.findByIdAndOrgId(any(), any())).thenAnswer(inv ->
                reviewId.equals(inv.getArgument(0)) && org.equals(inv.getArgument(1))
                        ? Optional.of(new Review()) : Optional.empty());
    }

    private static ReviewMedia m(int ordinal, ReviewMedia.Kind kind, ReviewMedia.InspectionStatus status) {
        ReviewMedia row = new ReviewMedia();
        row.setOrdinal(ordinal);
        row.setMediaKind(kind);
        row.setInspectionStatus(status);
        row.setSourceUrl("https://phinf.pstatic.net/x" + ordinal + ".jpg");
        if (status == ReviewMedia.InspectionStatus.INSPECTED) {
            row.setDepicts("포장 상자");
            row.setProblemVisible(ReviewMedia.ProblemVisible.YES);
            row.setProblemDescription("모서리가 찌그러져 있다");
        }
        return row;
    }

    private AuthPrincipal me() {
        return new AuthPrincipal(UUID.randomUUID(), org, "seller@example.com");
    }

    @Test
    @DisplayName("목록: 확인한 사진만 내용을 말하고, 동영상에는 이미지 경로가 없다")
    void listDescribesEachAttachment() {
        when(inspector.enabledFor(org)).thenReturn(true);
        when(media.findByOrgIdAndReviewIdOrderByOrdinalAsc(org, reviewId)).thenReturn(List.of(
                m(0, ReviewMedia.Kind.IMAGE, ReviewMedia.InspectionStatus.INSPECTED),
                m(1, ReviewMedia.Kind.IMAGE, ReviewMedia.InspectionStatus.NOT_INSPECTED),
                m(2, ReviewMedia.Kind.VIDEO, ReviewMedia.InspectionStatus.NOT_AN_IMAGE)));

        ReviewMediaView view = controller.list(me(), reviewId);

        assertThat(view.items()).hasSize(3);
        ReviewMediaView.Item seen = view.items().get(0);
        assertThat(seen.kind()).isEqualTo("IMAGE");
        assertThat(seen.inspected()).isTrue();
        assertThat(seen.depicts()).isEqualTo("포장 상자");
        assertThat(seen.problemVisible()).isEqualTo("YES");
        assertThat(seen.problemDescription()).isEqualTo("모서리가 찌그러져 있다");
        assertThat(seen.statusKo()).isEqualTo("Reviewnary가 사진을 확인했습니다.");
        assertThat(seen.imagePath()).isEqualTo("/api/reviews/" + reviewId + "/media/0");

        ReviewMediaView.Item unseen = view.items().get(1);
        assertThat(unseen.inspected()).isFalse();
        assertThat(unseen.depicts()).isNull();
        assertThat(unseen.problemVisible()).isNull();
        assertThat(unseen.statusKo()).isEqualTo("아직 사진을 확인하지 않았습니다.");
        assertThat(unseen.imagePath()).isEqualTo("/api/reviews/" + reviewId + "/media/1");

        ReviewMediaView.Item video = view.items().get(2);
        assertThat(video.kind()).isEqualTo("VIDEO");
        assertThat(video.imagePath()).isNull();
        // 원본 주소는 어디에도 실리지 않는다.
        assertThat(view.toString()).doesNotContain("pstatic");
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 꺼져 있다고 말한다 — 케이스 화면과 같은 문장(ReviewMediaPresentation)")
    void theSentenceIsShared() {
        assertThat(ReviewMediaPresentation.statusKo(ReviewMedia.InspectionStatus.NOT_INSPECTED, false))
                .isEqualTo("사진 확인 기능이 꺼져 있어 사진 내용은 보지 않았습니다.");
    }

    @Test
    @DisplayName("첨부가 없으면 빈 목록이다 — 404가 아니다")
    void noMediaIsAnEmptyList() {
        when(media.findByOrgIdAndReviewIdOrderByOrdinalAsc(org, reviewId)).thenReturn(List.of());
        assertThat(controller.list(me(), reviewId).items()).isEmpty();
    }

    @Test
    @DisplayName("다른 조직의 리뷰는 목록도 사진도 404 — 그리고 아무것도 가져오지 않는다")
    void anotherOrgsReviewIs404() {
        AuthPrincipal stranger = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), "x@example.com");
        assertThatThrownBy(() -> controller.list(stranger, reviewId)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        ResponseEntity<byte[]> photo = controller.image(stranger, reviewId, 0);
        assertThat(photo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(media, never()).findByOrgIdAndReviewIdOrderByOrdinalAsc(any(), any());
        verify(inspector, never()).loadForSeller(any());
    }

    @Test
    @DisplayName("사진 바이트: 케이스 경로와 같은 헤더, 없는 순번·동영상·거절된 fetch는 404")
    void imageBytes() {
        ReviewMedia photo = m(0, ReviewMedia.Kind.IMAGE, ReviewMedia.InspectionStatus.INSPECTED);
        ReviewMedia untyped = m(1, ReviewMedia.Kind.IMAGE, ReviewMedia.InspectionStatus.NOT_INSPECTED);
        ReviewMedia video = m(2, ReviewMedia.Kind.VIDEO, ReviewMedia.InspectionStatus.NOT_AN_IMAGE);
        ReviewMedia refused = m(3, ReviewMedia.Kind.IMAGE, ReviewMedia.InspectionStatus.FETCH_FAILED);
        when(media.findByOrgIdAndReviewIdOrderByOrdinalAsc(org, reviewId))
                .thenReturn(List.of(photo, untyped, video, refused));
        byte[] bytes = {1, 2, 3};
        when(inspector.loadForSeller(photo)).thenReturn(Optional.of(new DetailImageFetcher.Loaded(
                new FetchedImage(0, FetchedImage.Outcome.OK, "a".repeat(64), 3, "image/png", 1, 1), bytes)));
        when(inspector.loadForSeller(untyped)).thenReturn(Optional.of(new DetailImageFetcher.Loaded(
                new FetchedImage(1, FetchedImage.Outcome.OK, "b".repeat(64), 3, null, 1, 1), bytes)));
        when(inspector.loadForSeller(refused)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> ok = controller.image(me(), reviewId, 0);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo(bytes);
        assertThat(ok.getHeaders().getContentType()).hasToString("image/png");
        assertThat(ok.getHeaders().getFirst("Cache-Control")).isEqualTo("private, max-age=300");
        assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        assertThat(controller.image(me(), reviewId, 1).getHeaders().getContentType())
                .as("형식을 모르면 image/jpeg").hasToString("image/jpeg");
        assertThat(controller.image(me(), reviewId, 2).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(inspector, never()).loadForSeller(video);
        assertThat(controller.image(me(), reviewId, 3).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.image(me(), reviewId, 9).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
