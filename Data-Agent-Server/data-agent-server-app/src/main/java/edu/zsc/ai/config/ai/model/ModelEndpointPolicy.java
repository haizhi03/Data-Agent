package edu.zsc.ai.config.ai.model;

import java.net.*;
import java.util.*;

/** Used by the transport DNS resolver itself, avoiding validation/connection DNS races. */
public final class ModelEndpointPolicy {
    private ModelEndpointPolicy() {}
    public static URI normalize(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
                    || uri.getPath().contains("..") || uri.getRawPath().contains("%")) {
                throw new IllegalArgumentException();
            }
            String base = uri.toString();
            return URI.create(base.endsWith("/") ? base : base + "/");
        } catch (Exception e) {
            throw new IllegalStateException("Invalid model endpoint; expected HTTPS API base URL");
        }
    }
    public static List<InetAddress> resolve(String host, Set<String> allowedPrivateHosts) throws UnknownHostException {
        List<InetAddress> addresses = List.of(InetAddress.getAllByName(host));
        for (InetAddress address : addresses) {
            byte[] b = address.getAddress();
            int first = b[0] & 255;
            int second = b[1] & 255;
            // Metadata targets remain forbidden even with administrator private-host approval.
            boolean metadata = (b.length == 4 && first == 169 && second == 254)
                    || address.getHostAddress().equals("100.100.100.200")
                    || address.isLinkLocalAddress();
            boolean privateTarget = address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (b.length == 16 && (first & 0xfe) == 0xfc)
                    || (b.length == 4 && (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)));
            if (metadata || (privateTarget && !allowedPrivateHosts.contains(host.toLowerCase(Locale.ROOT)))) {
                throw new UnknownHostException("Model endpoint target is not permitted");
            }
        }
        return addresses;
    }
}
