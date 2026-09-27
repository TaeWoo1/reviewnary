package com.sellerops.reviewimport.unattended;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.device.HelperDeviceAuthFilter;
import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.common.ApiException;
import com.sellerops.reviewimport.ReviewImportLaunch;
import com.sellerops.reviewimport.ReviewImportLaunchKind;
import com.sellerops.reviewimport.ReviewImportLaunchService;
import com.sellerops.reviewimport.ReviewImportLaunchStatus;
import com.sellerops.reviewimport.dto.ReviewImportLaunchView;
import com.sellerops.selleraccount.SellerAccount;
import com.sellerops.selleraccount.SellerAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * What this route may and may not do. The interesting assertions are the refusals: each one is a way the
 * unattended lane could otherwise have become wider than the authority it was given.
 */
class UnattendedReviewExportControllerTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ORG = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NAVER_CHANNEL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACCOUNT = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private ReviewImportLaunchService launchService;
    private SellerAccountRepository accounts;
    private ChannelRepository channels;

    @BeforeEach
    void setUp() {
        launchService = mock(ReviewImportLaunchService.class);
        accounts = mock(SellerAccountRepository.class);
        channels = mock(ChannelRepository.class);
        Channel naver = new Channel();
        naver.setId(NAVER_CHANNEL);
        naver.setCode("NAVER");
        when(channels.findByCode("NAVER")).thenReturn(Optional.of(naver));
        when(launchService.mintNextForAccount(any(), any())).thenReturn(ticket());
        when(launchService.requiredDatesOf(any())).thenReturn(null);
    }

    private UnattendedReviewExportController controller(boolean enabled, String orgs, String devices) {
        return new UnattendedReviewExportController(
                new UnattendedReviewExportProperties(enabled, orgs, devices), launchService, accounts, channels);
    }

    private UnattendedReviewExportController admitted() {
        return controller(true, ORG.toString(), DEVICE.toString());
    }

    private static MockHttpServletRequest fromDevice(UUID deviceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HelperDeviceAuthFilter.DEVICE_ID_ATTRIBUTE, deviceId);
        return request;
    }

    private static AuthPrincipal principal(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, "agent@example.invalid");
    }

    private static ReviewImportLaunch ticket() {
        ReviewImportLaunch t = new ReviewImportLaunch();
        t.setId(UUID.randomUUID());
        t.setOrgId(ORG);
        t.setSellerAccountId(ACCOUNT);
        t.setChannelId(NAVER_CHANNEL);
        t.setLaunchRef("00112233445566aa");
        t.setKind(ReviewImportLaunchKind.SEGMENT);
        t.setStatus(ReviewImportLaunchStatus.ISSUED);
        return t;
    }

    private void oneNaverApiAccount() {
        SellerAccount a = new SellerAccount();
        a.setId(ACCOUNT);
        a.setOrgId(ORG);
        a.setChannelId(NAVER_CHANNEL);
        a.setFileUpload(false);
        when(accounts.findAllByOrgIdAndChannelId(ORG, NAVER_CHANNEL)).thenReturn(List.of(a));
    }

    @Test
    @DisplayName("named org + named device + capability on: mints for the org's NAVER account")
    void mintsWhenAdmitted() {
        oneNaverApiAccount();
        ReviewImportLaunchView view = admitted().nextLaunch(principal(ORG), fromDevice(DEVICE));
        assertThat(view).isNotNull();
        verify(launchService).mintNextForAccount(ORG, ACCOUNT);
    }

    @Test
    @DisplayName("capability off: refused, and nothing is minted")
    void refusedWhenOff() {
        oneNaverApiAccount();
        assertThatThrownBy(() -> controller(false, ORG.toString(), DEVICE.toString())
                .nextLaunch(principal(ORG), fromDevice(DEVICE)))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("an organisation this deployment did not name cannot mint, even from a named device")
    void refusesUnnamedOrg() {
        assertThatThrownBy(() -> admitted().nextLaunch(principal(OTHER_ORG), fromDevice(DEVICE)))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("a device this deployment did not name cannot mint, even for the named organisation")
    void refusesUnnamedDevice() {
        assertThatThrownBy(() -> admitted().nextLaunch(principal(ORG), fromDevice(UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("a seller's browser session cannot reach this route — no device id on the request")
    void refusesNonDeviceCaller() {
        oneNaverApiAccount();
        assertThatThrownBy(() -> admitted().nextLaunch(principal(ORG), new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("no connected NAVER account: refused rather than minting against nothing")
    void refusesWithoutNaverAccount() {
        when(accounts.findAllByOrgIdAndChannelId(ORG, NAVER_CHANNEL)).thenReturn(List.of());
        assertThatThrownBy(() -> admitted().nextLaunch(principal(ORG), fromDevice(DEVICE)))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("a file-upload NAVER account is not a seller centre to drive")
    void ignoresFileUploadAccount() {
        SellerAccount fileUpload = new SellerAccount();
        fileUpload.setId(UUID.randomUUID());
        fileUpload.setOrgId(ORG);
        fileUpload.setChannelId(NAVER_CHANNEL);
        fileUpload.setFileUpload(true);
        when(accounts.findAllByOrgIdAndChannelId(ORG, NAVER_CHANNEL)).thenReturn(List.of(fileUpload));
        assertThatThrownBy(() -> admitted().nextLaunch(principal(ORG), fromDevice(DEVICE)))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("two NAVER accounts: ambiguity is a refusal, not a guess about which store to drive")
    void refusesAmbiguousAccount() {
        SellerAccount a = new SellerAccount();
        a.setId(ACCOUNT);
        a.setOrgId(ORG);
        a.setChannelId(NAVER_CHANNEL);
        SellerAccount b = new SellerAccount();
        b.setId(UUID.randomUUID());
        b.setOrgId(ORG);
        b.setChannelId(NAVER_CHANNEL);
        when(accounts.findAllByOrgIdAndChannelId(ORG, NAVER_CHANNEL)).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> admitted().nextLaunch(principal(ORG), fromDevice(DEVICE)))
                .isInstanceOf(ApiException.class);
        verify(launchService, never()).mintNextForAccount(any(), any());
    }

    @Test
    @DisplayName("the channel is a constant: only the NAVER channel row is ever looked up")
    void channelIsNotAParameter() {
        oneNaverApiAccount();
        admitted().nextLaunch(principal(ORG), fromDevice(DEVICE));
        verify(channels).findByCode("NAVER");
    }
}
