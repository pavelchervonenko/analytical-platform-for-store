package com.storeanalytics.common.security;

import com.storeanalytics.common.config.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class ClientAddressResolver {

    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    static final int MAXIMUM_HEADER_LENGTH = 1024;
    static final int MAXIMUM_PROXY_HOPS = 16;

    private static final ClientAddress UNKNOWN_ADDRESS =
            new ClientAddress("unknown", "unknown");
    private static final Pattern IPV6_LITERAL_CHARACTERS = Pattern.compile(
            "[0-9A-Fa-f:.]+"
    );

    private final List<IpNetwork> trustedProxies;

    public ClientAddressResolver(ClientIpProperties properties) {
        this.trustedProxies = properties.trustedProxyCidrs()
                .stream()
                .map(IpNetwork::parse)
                .toList();
    }

    public ClientAddress resolve(HttpServletRequest request) {
        ParsedAddress directPeer = parseLiteral(request.getRemoteAddr())
                .orElse(null);
        if (directPeer == null) {
            return UNKNOWN_ADDRESS;
        }
        if (!isTrusted(directPeer)) {
            return directPeer.toClientAddress();
        }

        String forwardedFor = singleForwardedForHeader(request)
                .orElse(null);
        if (forwardedFor == null
                || forwardedFor.isBlank()
                || forwardedFor.length() > MAXIMUM_HEADER_LENGTH) {
            return directPeer.toClientAddress();
        }
        String[] chain = forwardedFor.split(",", -1);
        if (chain.length > MAXIMUM_PROXY_HOPS) {
            return directPeer.toClientAddress();
        }

        ParsedAddress current = directPeer;
        for (int index = chain.length - 1; index >= 0; index--) {
            if (!isTrusted(current)) {
                return current.toClientAddress();
            }
            Optional<ParsedAddress> candidate = parseLiteral(chain[index]);
            if (candidate.isEmpty()) {
                return directPeer.toClientAddress();
            }
            current = candidate.get();
        }
        return current.toClientAddress();
    }

    private Optional<String> singleForwardedForHeader(
            HttpServletRequest request
    ) {
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR_HEADER);
        if (headers == null || !headers.hasMoreElements()) {
            return Optional.empty();
        }
        String value = headers.nextElement();
        if (headers.hasMoreElements()) {
            return Optional.empty();
        }
        return Optional.ofNullable(value);
    }

    private boolean isTrusted(ParsedAddress address) {
        return trustedProxies.stream().anyMatch(network -> network.contains(
                address.bytes()
        ));
    }

    private static Optional<ParsedAddress> parseLiteral(String rawAddress) {
        if (rawAddress == null) {
            return Optional.empty();
        }
        String value = rawAddress.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || value.length() > 45 || value.contains("%")) {
            return Optional.empty();
        }
        if (!value.contains(":")) {
            return parseIpv4(value);
        }
        if (!IPV6_LITERAL_CHARACTERS.matcher(value).matches()) {
            return Optional.empty();
        }
        int lastColon = value.lastIndexOf(':');
        if (value.contains(".")
                && parseIpv4(value.substring(lastColon + 1)).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParsedAddress(
                    InetAddress.getByName(value).getAddress()
            ));
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ParsedAddress> parseIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return Optional.empty();
        }
        byte[] address = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty()
                    || (octet.length() > 1 && octet.startsWith("0"))
                    || !octet.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            try {
                int number = Integer.parseInt(octet);
                if (number > 255) {
                    return Optional.empty();
                }
                address[index] = (byte) number;
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
        return Optional.of(new ParsedAddress(address));
    }

    private record ParsedAddress(byte[] bytes) {

        private ParsedAddress {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        private ClientAddress toClientAddress() {
            String canonical = canonical(bytes);
            if (bytes.length == 4) {
                return new ClientAddress(canonical, canonical);
            }
            byte[] prefix = Arrays.copyOf(bytes, bytes.length);
            Arrays.fill(prefix, 8, prefix.length, (byte) 0);
            return new ClientAddress(
                    canonical,
                    canonical(prefix) + "/64"
            );
        }

        private static String canonical(byte[] address) {
            try {
                return InetAddress.getByAddress(address)
                        .getHostAddress()
                        .toLowerCase(Locale.ROOT);
            } catch (UnknownHostException exception) {
                throw new IllegalStateException(
                        "Validated IP address became invalid",
                        exception
                );
            }
        }
    }

    private record IpNetwork(byte[] address, int prefixLength) {

        private IpNetwork {
            address = address.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }

        private boolean contains(byte[] candidate) {
            if (address.length != candidate.length) {
                return false;
            }
            int fullBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != candidate[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff & (0xff << (Byte.SIZE - remainingBits));
            return (address[fullBytes] & mask)
                    == (candidate[fullBytes] & mask);
        }

        private static IpNetwork parse(String configuredRange) {
            String[] parts = configuredRange.split("/", -1);
            if (parts.length > 2) {
                throw invalidRange(configuredRange);
            }
            ParsedAddress parsed = parseLiteral(parts[0])
                    .orElseThrow(() -> invalidRange(configuredRange));
            byte[] address = parsed.bytes();
            int maximumPrefix = address.length * Byte.SIZE;
            int prefix = parts.length == 1
                    ? maximumPrefix
                    : parsePrefix(parts[1], configuredRange);
            if (prefix < 1 || prefix > maximumPrefix
                    || hasHostBits(address, prefix)) {
                throw invalidRange(configuredRange);
            }
            return new IpNetwork(address, prefix);
        }

        private static int parsePrefix(
                String value,
                String configuredRange
        ) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw invalidRange(configuredRange);
            }
        }

        private static boolean hasHostBits(byte[] address, int prefix) {
            int fullBytes = prefix / Byte.SIZE;
            int remainingBits = prefix % Byte.SIZE;
            if (remainingBits != 0) {
                int hostMask = 0xff >>> remainingBits;
                if ((address[fullBytes] & hostMask) != 0) {
                    return true;
                }
                fullBytes++;
            }
            for (int index = fullBytes; index < address.length; index++) {
                if (address[index] != 0) {
                    return true;
                }
            }
            return false;
        }

        private static IllegalArgumentException invalidRange(String range) {
            return new IllegalArgumentException(
                    "Trusted proxy range is invalid: " + range
            );
        }
    }
}
