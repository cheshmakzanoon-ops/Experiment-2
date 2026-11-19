package com.artflow.qualification;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/** Sends one advertised action to the largest eligible visible scroll container. */
final class AccessibleScroll {
    private AccessibleScroll() {}

    static <T, A> boolean request(List<T> containers, ToIntFunction<T> visibleHeight, int minimumHeight,
            A action, BiPredicate<T, A> advertised, BiPredicate<T, A> perform) {
        T selected = null;
        int largest = minimumHeight - 1;
        for (T container : containers) {
            int height = visibleHeight.applyAsInt(container);
            if (height > largest) {
                selected = container;
                largest = height;
            }
        }
        return selected != null && advertised.test(selected, action) && perform.test(selected, action);
    }
}
