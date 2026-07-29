package com.storeanalytics.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.storeanalytics.common.config.ClientIpProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        ClientAddress address = resolve(
                List.of("172.20.0.0/24"),
                "203.0.113.10",
                "198.51.100.25"
        );

        assertThat(address.canonicalAddress()).isEqualTo("203.0.113.10");
        assertThat(address.throttleKey()).isEqualTo("203.0.113.10");
    }

    @Test
    void acceptsForwardedAddressFromTrustedPeer() {
        ClientAddress address = resolve(
                List.of("172.20.0.0/24"),
                "172.20.0.10",
                "198.51.100.25"
        );

        assertThat(address.canonicalAddress()).isEqualTo("198.51.100.25");
    }

    @Test
    void walksTrustedProxyChainFromRightToLeft() {
        ClientAddress address = resolve(
                List.of("172.20.0.0/24", "192.0.2.10"),
                "172.20.0.10",
                "198.51.100.25, 192.0.2.10"
        );

        assertThat(address.canonicalAddress()).isEqualTo("198.51.100.25");
    }

    @Test
    void ignoresSpoofedValuesLeftOfFirstUntrustedHop() {
        ClientAddress address = resolve(
                List.of("172.20.0.0/24"),
                "172.20.0.10",
                "198.51.100.99, 203.0.113.15"
        );

        assertThat(address.canonicalAddress()).isEqualTo("203.0.113.15");
    }

    @Test
    void fallsBackToDirectPeerForMalformedOrAmbiguousHeader() {
        ClientAddress malformed = resolve(
                List.of("172.20.0.0/24"),
                "172.20.0.10",
                "not-an-ip"
        );
        MockHttpServletRequest duplicateHeaders = request(
                "172.20.0.10",
                "198.51.100.20"
        );
        duplicateHeaders.addHeader(
                ClientAddressResolver.FORWARDED_FOR_HEADER,
                "198.51.100.21"
        );
        ClientAddress ambiguous = resolver(List.of("172.20.0.0/24"))
                .resolve(duplicateHeaders);

        assertThat(malformed.canonicalAddress()).isEqualTo("172.20.0.10");
        assertThat(ambiguous.canonicalAddress()).isEqualTo("172.20.0.10");
    }

    @Test
    void usesIpv6PrefixForNatSafeThrottleKey() {
        ClientAddress first = resolve(
                List.of("fd00::/8"),
                "fd00::10",
                "2001:db8:abcd:1234::1"
        );
        ClientAddress second = resolve(
                List.of("fd00::/8"),
                "fd00::10",
                "2001:db8:abcd:1234:ffff::2"
        );
        ClientAddress otherNetwork = resolve(
                List.of("fd00::/8"),
                "fd00::10",
                "2001:db8:abcd:5678::1"
        );

        assertThat(first.canonicalAddress())
                .isEqualTo("2001:db8:abcd:1234:0:0:0:1");
        assertThat(first.throttleKey()).isEqualTo(second.throttleKey());
        assertThat(first.throttleKey())
                .isNotEqualTo(otherNetwork.throttleKey())
                .endsWith("/64");
    }

    @Test
    void normalizesIpv4MappedIpv6AddressToIpv4() {
        ClientAddress address = resolve(
                List.of("fd00::/8"),
                "fd00::10",
                "::ffff:192.0.2.25"
        );

        assertThat(address.canonicalAddress()).isEqualTo("192.0.2.25");
        assertThat(address.throttleKey()).isEqualTo("192.0.2.25");
    }

    @Test
    void supportsNonByteAlignedTrustedRange() {
        ClientAddress insideRange = resolve(
                List.of("192.0.2.128/25"),
                "192.0.2.200",
                "198.51.100.20"
        );
        ClientAddress outsideRange = resolve(
                List.of("192.0.2.128/25"),
                "192.0.2.100",
                "198.51.100.20"
        );

        assertThat(insideRange.canonicalAddress())
                .isEqualTo("198.51.100.20");
        assertThat(outsideRange.canonicalAddress())
                .isEqualTo("192.0.2.100");
    }

    @Test
    void rejectsInvalidOrOverlyBroadTrustedRanges() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver(List.of("0.0.0.0/0")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver(List.of("172.20.0.1/24")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> resolver(List.of("proxy.example.com")));
    }

    private ClientAddress resolve(
            List<String> trustedRanges,
            String remoteAddress,
            String forwardedFor
    ) {
        return resolver(trustedRanges)
                .resolve(request(remoteAddress, forwardedFor));
    }

    private ClientAddressResolver resolver(List<String> trustedRanges) {
        return new ClientAddressResolver(new ClientIpProperties(trustedRanges));
    }

    private MockHttpServletRequest request(
            String remoteAddress,
            String forwardedFor
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader(
                    ClientAddressResolver.FORWARDED_FOR_HEADER,
                    forwardedFor
            );
        }
        return request;
    }
}
