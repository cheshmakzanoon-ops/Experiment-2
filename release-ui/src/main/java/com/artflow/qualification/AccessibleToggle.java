package com.artflow.qualification;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Resolves a visible label to its own nearest checkable accessibility ancestor. */
final class AccessibleToggle {
    private AccessibleToggle() {}

    static <T> T resolve(List<T> labels, Function<T, T> parent,
            Predicate<T> inVisibleApplication, Predicate<T> checkable) {
        Set<T> controls = new HashSet<>();
        for (T label : labels) {
            for (T node = label; node != null && inVisibleApplication.test(node); node = parent.apply(node)) {
                if (checkable.test(node)) {
                    controls.add(node);
                    break;
                }
            }
        }
        if (controls.size() > 1) throw new AssertionError("Ambiguous labeled toggle: " + controls.size());
        return controls.isEmpty() ? null : controls.iterator().next();
    }
}
