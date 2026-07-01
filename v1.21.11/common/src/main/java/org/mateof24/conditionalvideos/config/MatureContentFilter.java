package org.mateof24.conditionalvideos.config;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class MatureContentFilter {
    private static final Set<String> BLOCKED_DOMAINS = Set.of(
            "pornhub.com",
            "xvideos.com",
            "xhamster.com",
            "redtube.com",
            "youporn.com",
            "xnxx.com",
            "spankbang.com",
            "tube8.com",
            "youjizz.com",
            "chaturbate.com",
            "stripchat.com",
            "brazzers.com",
            "onlyfans.com",
            "fansly.com",
            "rule34.xxx",
            "e-hentai.org"
    );

    private MatureContentFilter() {
    }

    public static boolean isMatureUrl(URI uri) {
        if (uri == null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        for (String domain : BLOCKED_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }
}
