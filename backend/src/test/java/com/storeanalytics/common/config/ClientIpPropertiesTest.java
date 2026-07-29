package com.storeanalytics.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpPropertiesTest {

    @Test
    void normalizesAndDeduplicatesConfiguredRanges() {
        ClientIpProperties properties = new ClientIpProperties(List.of(
                " 172.20.0.0/24 ",
                "172.20.0.0/24",
                "2001:db8::/32"
        ));

        assertThat(properties.trustedProxyCidrs()).containsExactly(
                "172.20.0.0/24",
                "2001:db8::/32"
        );
    }

    @Test
    void treatsMissingConfigurationAsNoTrustedProxies() {
        assertThat(new ClientIpProperties(null).trustedProxyCidrs()).isEmpty();
        assertThat(new ClientIpProperties(List.of(" ")).trustedProxyCidrs())
                .isEmpty();
    }

    @Test
    void rejectsUnboundedNumberOfTrustedRanges() {
        List<String> ranges = new ArrayList<>();
        for (int index = 1; index <= 33; index++) {
            ranges.add("192.0.2." + index);
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ClientIpProperties(ranges));
    }
}
