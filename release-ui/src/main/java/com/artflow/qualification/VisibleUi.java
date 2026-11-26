package com.artflow.qualification;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import java.util.ArrayList;
import java.util.List;

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
        UiObject2 value = device.wait(ignored -> freshVisible(selector), TIMEOUT);
        if (value == null) throw new AssertionError("Missing visible UI: " + selector);
        return value;
    }

    private UiObject2 freshVisible(BySelector selector) {
        // Compose filters UIAutomator out of its layout-event listeners. Cached descendants
        // can therefore retain old labels/bounds after a successful scroll or recomposition.
        // This ordinary-release qualification runs on API 36; use only the public cache API.
        if (Build.VERSION.SDK_INT < 34 || !instrumentation.getUiAutomation().clearCache()) {
            throw new AssertionError("A fresh visible tree requires a connected API 34+ accessibility cache");
        }
        // An asynchronous export can replace a node between lookup and its bounds read,
        // even after clearing the cache. Treat only that detached observation as unavailable;
        // the existing bounded wait/scroll will query a fresh tree, without replaying an action.
        return AccessibleObservation.visible(() -> device.findObject(selector),
                node -> !node.getVisibleBounds().isEmpty(), StaleObjectException.class);
    }

    void text(String value) { scroll(By.pkg(APP).text(value)).click(); }
    void description(String value) { scroll(By.pkg(APP).desc(value)).click(); }

    UiObject2 scroll(BySelector target) {
        return scroll(target, By.pkg(APP).scrollable(true), APP);
    }

    UiObject2 scroll(BySelector target, BySelector containers, String packageName) {
        UiObject2 present = freshVisible(target);
        if (present != null) return present;
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP}) {
            for (int attempt = 0; attempt < 12; attempt++) {
                List<UiObject2> candidates = device.findObjects(containers);
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
                        (node, requested) -> scrollAndWait(node, requested, target, packageName));
                device.waitForIdle(1_000);
                logVisibleText(target, packageName);
                present = freshVisible(target);
                if (present != null) return present;
                if (!more) break;
            }
        }
        return await(target);
    }

    private boolean scrollAndWait(UiObject2 container,
            AccessibilityNodeInfo.AccessibilityAction action, BySelector target, String packageName) {
        String before = visibleContainerContent(container, packageName);
        boolean accepted = container.getAccessibilityNodeInfo().performAction(action.getId());
        Log.i("ArtFlowQualification", "Scroll action=" + action.getId()
                + "; accepted=" + accepted + "; target=" + target + "; before=" + before);
        if (!accepted) return false;
        // Compose deliberately omits TYPE_VIEW_SCROLLED for a UIAutomator-only connection.
        // Observe the result of this one action instead of waiting for an event it does not send.
        boolean changed = device.wait(ignored -> {
            if (freshVisible(target) != null) return true;
            return !before.equals(visibleContainerContent(container, packageName));
        }, 1_000);
        Log.i("ArtFlowQualification", "Observed target or changed viewport for " + target
                + ": " + changed + "; after=" + visibleContainerContent(container, packageName));
        return changed;
    }

    private String visibleContainerContent(UiObject2 container, String packageName) {
        // UiObject2 owns its cached node; recycle only this copy and the children we obtain.
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain(container.getAccessibilityNodeInfo());
        StringBuilder content = new StringBuilder();
        try { collectVisibleText(root, content, packageName); }
        finally { root.recycle(); }
        return content.toString();
    }

    private void logVisibleText(BySelector target, String packageName) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        StringBuilder text = new StringBuilder();
        try { collectVisibleText(root, text, packageName); }
        finally { if (root != null) root.recycle(); }
        Log.i("ArtFlowQualification", "Visible text while seeking " + target + ": " + text);
    }

    private void collectVisibleText(AccessibilityNodeInfo node, StringBuilder text, String packageName) {
        if (node == null) return;
        if (node.isVisibleToUser() && packageName.contentEquals(String.valueOf(node.getPackageName()))) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (node.getText() != null) text.append('[').append(node.getText()).append('@').append(bounds).append("] ");
            if (node.getHintText() != null) text.append("hint[").append(node.getHintText()).append('@').append(bounds).append("] ");
            if (node.getContentDescription() != null) {
                text.append("desc[").append(node.getContentDescription()).append('@').append(bounds).append("] ");
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { collectVisibleText(child, text, packageName); }
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
