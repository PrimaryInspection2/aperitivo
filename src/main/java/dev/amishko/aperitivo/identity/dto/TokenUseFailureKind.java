package dev.amishko.aperitivo.identity.dto;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum TokenUseFailureKind {
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK_ERROR,
    UNKNOWN;

    public static Set<TokenUseFailureKind> asSet() {
        return Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(values())));
    }
}
