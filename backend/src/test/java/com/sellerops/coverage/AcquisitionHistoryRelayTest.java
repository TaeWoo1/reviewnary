package com.sellerops.coverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.channel.ChannelRepository;
import com.sellerops.reviewimport.ReviewImportSegmentAttemptRepository;
import com.sellerops.sync.SyncJobRepository;
import com.sellerops.vendorlink.VendorStoreBinding;
import com.sellerops.vendorlink.VendorStoreBindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seller PC's NAVER reviews arrive only through the vendor server (a delegate uploads the export, this PC pulls
 * it). On the real-export run (2026-10-26) the channel line still said 「네이버 수집 기록 없음」 over 3,555 reviews
 * pulled that morning: a relayed upload was no record {@link AcquisitionHistory} read.
 */
class AcquisitionHistoryRelayTest {

    static final UUID ORG = UUID.randomUUID();
    static final UUID NAVER = UUID.randomUUID();
    static final Instant UPLOADED = Instant.parse("2026-10-25T17:29:45Z");

    SyncJobRepository syncJobs = mock(SyncJobRepository.class);
    ReviewImportSegmentAttemptRepository acquisitions = mock(ReviewImportSegmentAttemptRepository.class);
    VendorStoreBindingRepository relays = mock(VendorStoreBindingRepository.class);
    AcquisitionHistory history;

    @BeforeEach
    void setUp() {
        when(syncJobs.findLatestSuccessfulRun(any(), any(), any())).thenReturn(Optional.empty());
        when(acquisitions.lastSucceededAt(any(), any())).thenReturn(null);
        history = new AcquisitionHistory(syncJobs, acquisitions, mock(ChannelRepository.class), null, relays);
    }

    VendorStoreBinding binding(Instant pulled, String failure) {
        VendorStoreBinding b = new VendorStoreBinding();
        b.setOrgId(ORG);
        b.setChannelId(NAVER);
        b.setLastUploadAt(UPLOADED);
        b.setLastPulledAt(pulled);
        b.setLastFailure(failure);
        return b;
    }

    @Test
    @DisplayName("a pulled upload is the review lane's last read, at the upload's time")
    void aPulledUploadIsARead() {
        when(relays.findAllByOrgIdOrderByCreatedAt(ORG)).thenReturn(List.of(binding(UPLOADED.plusSeconds(180), null)));
        assertThat(history.lastSuccessAt(ORG, NAVER, "REVIEW")).isEqualTo(UPLOADED);
        // Reviews only: an upload says nothing about inquiries or orders.
        assertThat(history.lastSuccessAt(ORG, NAVER, "INQUIRY")).isNull();
        assertThat(history.lastSuccessAt(ORG, UUID.randomUUID(), "REVIEW")).isNull();
    }

    @Test
    @DisplayName("an upload this PC has not taken, or whose pull failed, is not a read")
    void anUntakenUploadIsNotARead() {
        when(relays.findAllByOrgIdOrderByCreatedAt(ORG)).thenReturn(List.of(binding(UPLOADED.minusSeconds(60), null)));
        assertThat(history.lastSuccessAt(ORG, NAVER, "REVIEW")).isNull();
        when(relays.findAllByOrgIdOrderByCreatedAt(ORG))
                .thenReturn(List.of(binding(UPLOADED.plusSeconds(60), "UNREACHABLE")));
        assertThat(history.lastSuccessAt(ORG, NAVER, "REVIEW")).isNull();
    }

    @Test
    @DisplayName("a deployment with no relay answers as before")
    void noRelayNoChange() {
        AcquisitionHistory without = new AcquisitionHistory(syncJobs, acquisitions, mock(ChannelRepository.class), null);
        assertThat(without.lastSuccessAt(ORG, NAVER, "REVIEW")).isNull();
    }
}
