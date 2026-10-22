package com.sellerops.review.draft;

import com.sellerops.channel.Channel;
import com.sellerops.channel.ChannelRepository;
import com.sellerops.channel.ChannelStatus;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import java.util.UUID;

/**
 * The precedent tests' organisations and channel. The tests are {@code @DataJpaTest}s and roll back; the channel still
 * has a code of its own, never a product channel's — the shared in-memory database may already hold the catalogue,
 * and other classes insert NAVER or COUPANG themselves.
 */
final class PrecedentFixture {

    private final ChannelRepository channels;
    private final OrganizationRepository organizations;
    private UUID channel;

    PrecedentFixture(ChannelRepository channels, OrganizationRepository organizations) {
        this.channels = channels;
        this.organizations = organizations;
    }

    UUID organization(String name) {
        Organization o = new Organization();
        o.setName(name);
        return organizations.save(o).getId();
    }

    UUID channel() {
        if (channel == null) {
            Channel c = new Channel();
            c.setCode("PRECEDENT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            c.setNameKo("선례 시험 채널");
            c.setStatus(ChannelStatus.AVAILABLE);
            c.setSupportsReview(true);
            c.setSortOrder(999);
            channel = channels.save(c).getId();
        }
        return channel;
    }
}
