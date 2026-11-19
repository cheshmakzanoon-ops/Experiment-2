package com.artflow.qualification;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Bounded operations against the visible accessibility tree, without production classes or state. */
final class VisibleUi {
    static final String APP = "com.artflow.studio";
    static final long TIMEOUT = 15_000;
    final UiDevice device;
    private final Instrumentation instrumentation;

    VisibleUi(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        device = UiDevice.getInstance(instrumentation);
    }

    UiObject2 await(BySelector selector) {
        UiObject2 value = device.wait(Until.findObject(selector), TIMEOUT);
        if (value == null) throw new AssertionError("Missing visible UI: " + selector);
        return value;
    }

    void text(String value) { scroll(By.pkg(APP).text(value)).click(); }
    void description(String value) { scroll(By.pkg(APP).desc(value)).click(); }

    UiObject2 scroll(BySelector target) {
        UiObject2 present = device.findObject(target);
        if (present != null && !present.getVisibleBounds().isEmpty()) return present;
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (int attempt = 0; attempt < 12; attempt++) {
                List<UiObject2> candidates = device.findObjects(By.pkg(APP).scrollable(true));
                AccessibilityNodeInfo.AccessibilityAction action = direction == Direction.DOWN
                        ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
                // A pointer swipe can begin on an embedded slider and never scroll its parent.
                // Use the visible container's own advertised accessibility action instead.
                boolean more = AccessibleScroll.request(candidates, node -> node.getVisibleBounds().height(),
                        device.getDisplayHeight() / 4, action,
                        (node, requested) -> {
                            boolean advertised = node.getAccessibilityNodeInfo().getActionList().contains(requested);
                            Log.i("ArtFlowQualification", "Scroll " + direction + " in " + node.getClassName()
                                    + " " + node.getVisibleBounds() + "; advertised=" + advertised);
                            return advertised;
                        },
                        (node, requested) -> scrollAndWait(node, requested, target));
                device.waitForIdle(1_000);
                logVisibleText(target);
                present = device.findObject(target);
                if (present != null && !present.getVisibleBounds().isEmpty()) return present;
                if (!more) break;
            }
        }
        return await(target);
    }

    private boolean scrollAndWait(UiObject2 container,
            AccessibilityNodeInfo.AccessibilityAction action, BySelector target) {
        AccessibilityNodeInfo requestedContainer = container.getAccessibilityNodeInfo();
        boolean[] accepted = {false};
        try {
            // Register before the action. A quiet accessibility stream before an asynchronous
            // scroll starts is not evidence that the resulting screen has been observed.
            AccessibilityEvent event = instrumentation.getUiAutomation().executeAndWaitForEvent(
                    () -> {
                        accepted[0] = requestedContainer.performAction(action.getId());
                        Log.i("ArtFlowQualification", "Scroll action=" + action.getId()
                                + "; accepted=" + accepted[0] + "; target=" + target);
                    },
                    candidate -> {
                        if (candidate.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED
                                || !APP.contentEquals(String.valueOf(candidate.getPackageName()))) return false;
                        AccessibilityNodeInfo source = candidate.getSource();
                        try {
                            boolean matches = requestedContainer.equals(source);
                            Log.i("ArtFlowQualification", "Scroll event for " + target + ": selectedContainer="
                                    + matches + ", y=" + candidate.getScrollY() + "/" + candidate.getMaxScrollY());
                            return matches;
                        }
                        finally { if (source != null) source.recycle(); }
                    }, 1_000);
            try {
                Log.i("ArtFlowQualification", "Observed scroll for " + target + ": y=" + event.getScrollY()
                        + "/" + event.getMaxScrollY() + ", x=" + event.getScrollX()
                        + "/" + event.getMaxScrollX());
                return accepted[0];
            } finally { event.recycle(); }
        } catch (TimeoutException failure) {
            if (!accepted[0]) return false;
            throw new AssertionError("Accepted scroll produced no matching container event for " + target, failure);
        }
    }

    private void logVisibleText(BySelector target) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        StringBuilder text = new StringBuilder();
        try { collectVisibleText(root, text); }
        finally { if (root != null) root.recycle(); }
        Log.i("ArtFlowQualification", "Visible text while seeking " + target + ": " + text);
    }

    private void collectVisibleText(AccessibilityNodeInfo node, StringBuilder text) {
        if (node == null) return;
        if (node.isVisibleToUser() && APP.contentEquals(String.valueOf(node.getPackageName()))) {
            if (node.getText() != null) text.append('[').append(node.getText()).append("] ");
            if (node.getHintText() != null) text.append("hint[").append(node.getHintText()).append("] ");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { collectVisibleText(child, text); }
            finally { if (child != null) child.recycle(); }
        }
    }

    void toggle(String label, boolean desired, boolean description) {
        BySelector labeled = description ? By.pkg(APP).desc(label) : By.pkg(APP).text(label);
        scroll(labeled);
        UiObject2 node = toggleTarget(labeled);
        if (node == null || !node.isEnabled() || !node.isClickable()) {
            throw new AssertionError("No enabled, uniquely labeled toggle: " + label);
        }
        if (node.isChecked() != desired) node.click();
        if (!device.wait(ignored -> {
            UiObject2 current = toggleTarget(labeled);
            return current != null && current.isEnabled() && current.isChecked() == desired;
        }, TIMEOUT)) {
            throw new AssertionError("Toggle did not become " + desired + ": " + label);
        }
    }

    private UiObject2 toggleTarget(BySelector labeled) {
        // Compose can expose the label as a TextView child of the checkable row.
        // Walk only its visible application ancestors; never choose an unrelated switch.
        return AccessibleToggle.resolve(device.findObjects(labeled), UiObject2::getParent,
                node -> APP.equals(node.getApplicationPackage()) && !node.getVisibleBounds().isEmpty(),
                UiObject2::isCheckable);
    }

    void field(String label, String value) {
        // Compose exposes text-field labels as hint/text/descendant nodes depending on its
        // accessibility version. Every match must be editable, visible and contain this label.
        scroll(By.pkg(APP).textContains(label));
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectEditable(instrumentation.getUiAutomation().getRootInActiveWindow(), label, matches);
        if (matches.size() != 1) throw new AssertionError("Expected one editable " + label + ", saw " + matches.size());
        Bundle argument = new Bundle();
        argument.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!matches.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argument)) {
            throw new AssertionError("Visible text input refused: " + label);
        }
        device.waitForIdle(1_000);
    }

    private void collectEditable(AccessibilityNodeInfo node, String label, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.isEditable() && hasLabel(node, label)) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectEditable(node.getChild(i), label, result);
    }

    private boolean hasLabel(AccessibilityNodeInfo node, String label) {
        for (CharSequence value : new CharSequence[]{node.getHintText(), node.getText(), node.getContentDescription()}) {
            if (value != null && value.toString().contains(label)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && hasLabel(child, label)) return true;
        }
        return false;
    }

    void awaitCanvas() {
        await(By.pkg(APP).desc("Actions"));
        await(By.pkg(APP).text("Gallery"));
        // Rendering readiness is observed on screen. The driver has no private preview hook.
        long deadline = SystemClock.uptimeMillis() + TIMEOUT;
        do {
            Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
            if (screen != null && screen.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap readable = screen.copy(Bitmap.Config.ARGB_8888, false);
                screen.recycle();
                screen = readable;
            }
            if (screen != null) {
                try {
                    int white = 0;
                    for (int y = 45; y <= 55; y += 2) for (int x = 45; x <= 55; x += 2) {
                        int pixel = screen.getPixel(screen.getWidth() * x / 100, screen.getHeight() * y / 100);
                        if ((pixel & 0xFFFFFF) == 0xFFFFFF) white++;
                    }
                    if (white >= 30) return;
                } finally { screen.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The centered white canvas was not visibly rendered");
    }

    void paint() {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        // The observed centered paper occupies this unobstructed region between the toolbars.
        if (!device.swipe(width * 35 / 100, height * 48 / 100,
                width * 65 / 100, height * 52 / 100, 28)) {
            throw new AssertionError("Pointer stroke injection failed");
        }
        await(By.pkg(APP).desc("Undo").enabled(true));
    }
}
