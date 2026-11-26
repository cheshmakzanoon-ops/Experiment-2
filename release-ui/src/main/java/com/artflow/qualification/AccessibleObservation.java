package com.artflow.qualification;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** One read of a visible node; a detached handle is not a successful observation. */
final class AccessibleObservation {
    private AccessibleObservation() {}

    static <T> T visible(Supplier<T> lookup, Predicate<T> visible,
            Class<? extends RuntimeException> detachedNode) {
        try {
            T node = lookup.get();
            return node != null && visible.test(node) ? node : null;
        } catch (RuntimeException failure) {
            if (!detachedNode.isInstance(failure)) throw failure;
            // The caller owns the existing wait/scroll bounds. Do not repeat a read here,
            // and never wrap a click, scroll action, text input, or paint gesture in this helper.
            return null;
        }
    }
}
