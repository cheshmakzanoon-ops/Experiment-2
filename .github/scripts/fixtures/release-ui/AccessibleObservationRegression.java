package com.artflow.qualification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual read helper at the lookup/bounds boundary in the retained failure. */
public final class AccessibleObservationRegression {
    private static final class DetachedNode extends RuntimeException {}

    public static void main(String[] args) throws Exception {
        String failure = Files.readString(Path.of(args[1]));
        require(failure.contains("androidx.test.uiautomator.StaleObjectException")
                        && failure.contains("UiObject2.getVisibleBounds(UiObject2.java:327)")
                        && failure.contains("VisibleUi.freshVisible(VisibleUi.java:45)")
                        && failure.contains("ReleasePayloadWorkflowTest.export(ReleasePayloadWorkflowTest.java:118)"),
                "Missing original stale export-result observation evidence");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger bounds = new AtomicInteger();
        Object current = new Object();
        switch (args[0]) {
            case "observation-visible":
                require(AccessibleObservation.visible(() -> {
                    reads.incrementAndGet();
                    return current;
                }, node -> {
                    bounds.incrementAndGet();
                    return true;
                }, DetachedNode.class) == current, "The current visible node was not returned");
                require(reads.get() == 1 && bounds.get() == 1, "A single observation repeated its work");
                break;
            case "observation-missing":
                require(AccessibleObservation.visible(() -> {
                    reads.incrementAndGet();
                    return null;
                }, node -> {
                    bounds.incrementAndGet();
                    return true;
                }, DetachedNode.class) == null, "A missing target was accepted");
                require(reads.get() == 1 && bounds.get() == 0, "A missing target was read again or inspected");
                break;
            case "observation-hidden":
                require(AccessibleObservation.visible(() -> current, node -> false, DetachedNode.class) == null,
                        "An offscreen target was accepted");
                break;
            case "observation-detached-lookup":
                require(AccessibleObservation.visible(() -> {
                    reads.incrementAndGet();
                    throw new DetachedNode();
                }, node -> {
                    bounds.incrementAndGet();
                    return true;
                }, DetachedNode.class) == null, "A detached lookup was accepted");
                require(reads.get() == 1 && bounds.get() == 0, "Detached lookup was repeated or used");
                break;
            case "observation-detached-bounds":
                Object detached = new Object();
                java.util.function.Supplier<Object> lookup = () -> reads.incrementAndGet() == 1 ? detached : current;
                java.util.function.Predicate<Object> visible = node -> {
                    bounds.incrementAndGet();
                    if (node == detached) throw new DetachedNode();
                    return true;
                };
                require(AccessibleObservation.visible(lookup, visible, DetachedNode.class) == null,
                        "A node detached between lookup and bounds must remain unobserved");
                require(reads.get() == 1 && bounds.get() == 1,
                        "The helper must return to its caller without an internal retry");
                require(AccessibleObservation.visible(lookup, visible, DetachedNode.class) == current,
                        "The next bounded observation must obtain the new node, not reuse the detached handle");
                require(reads.get() == 2 && bounds.get() == 2, "The next observation did not read exactly once");
                break;
            case "observation-unrelated-failure":
                IllegalStateException unrelated = new IllegalStateException("accessibility disconnected");
                try {
                    AccessibleObservation.visible(() -> { throw unrelated; }, node -> true, DetachedNode.class);
                    throw new AssertionError("An unrelated lookup failure was hidden");
                } catch (IllegalStateException observed) {
                    require(observed == unrelated, "The lookup failure was replaced");
                }
                try {
                    AccessibleObservation.visible(() -> current, node -> { throw unrelated; }, DetachedNode.class);
                    throw new AssertionError("An unrelated bounds failure was hidden");
                } catch (IllegalStateException observed) {
                    require(observed == unrelated, "The bounds failure was replaced");
                }
                break;
            case "observation-assertion-failure":
                AssertionError assertion = new AssertionError("visible UI contract failed");
                try {
                    AccessibleObservation.visible(() -> current, node -> { throw assertion; }, DetachedNode.class);
                    throw new AssertionError("A qualification assertion was hidden");
                } catch (AssertionError observed) {
                    require(observed == assertion, "The original qualification assertion must propagate");
                }
                break;
            default:
                throw new AssertionError("Unknown regression case: " + args[0]);
        }
        System.out.println("PASS " + args[0]);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
