package com.artflow.qualification;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** One real release-process workflow, with only public package metadata and visible UI access. */
@RunWith(AndroidJUnit4.class)
public final class ReleasePayloadWorkflowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Bundle arguments = InstrumentationRegistry.getArguments();
    private final VisibleUi ui = new VisibleUi(instrumentation);
    private File output;
    private String token;

    @Test public void paintSaveProcessDeathReopenAndSharePreservePixels() throws Exception {
        token = required("releaseUiToken", "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        String revision = required("releaseUiRevision", "[0-9a-f]{40}");
        String appHash = required("releaseUiApkSha256", "[0-9a-f]{64}");
        String driverHash = required("releaseUiDriverSha256", "[0-9a-f]{64}");
        assertEquals("true", arguments.getString("freshDisposableInstall"));
        assertEquals("The runner must instrument only the driver APK", "com.artflow.qualification", context.getPackageName());
        assertEquals(context.getPackageName(), instrumentation.getContext().getPackageName());
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(VisibleUi.APP, 0);
        assertEquals("The actual release must be non-debuggable", 0, application.flags & ApplicationInfo.FLAG_DEBUGGABLE);
        assertEquals("The actual release must not be testOnly", 0, application.flags & ApplicationInfo.FLAG_TEST_ONLY);
        assertEquals(appHash, PngReceiptActivity.digest(Files.readAllBytes(new File(application.sourceDir).toPath())));
        assertEquals(driverHash, PngReceiptActivity.digest(Files.readAllBytes(new File(context.getApplicationInfo().sourceDir).toPath())));
        output = new File(context.getExternalFilesDir(null), "release-ui/" + token);
        assertTrue("Keep the original attempt", output.mkdirs());
        Files.write(new File(output, "started.txt").toPath(), token.getBytes(StandardCharsets.UTF_8));
        try {
            launch();
            ui.text("Skip");
            ui.await(By.pkg(VisibleUi.APP).desc("New artwork"));
            ui.description("Settings");
            ui.text("Light");
            ui.toggle("Stylus only", false, false);
            ui.toggle("Autosave", false, false);
            ui.description("Back");
            String name = "Release payload " + token.substring(0, 8);
            ui.description("New artwork");
            ui.field("Name", name);
            ui.text("Custom");
            ui.field("Width", "64");
            ui.field("Height", "64");
            ui.field("Dpi", "72");
            ui.text("Create");
            ui.awaitCanvas();
            capture("blank");
            int firstPid = appPid();
            assertNotEquals("App and instrumentation must be separate processes", Process.myPid(), firstPid);
            ui.paint();
            capture("painted");
            ui.text("Gallery");
            ui.await(By.pkg(VisibleUi.APP).text("Save changes?"));
            ui.text("Save and leave");
            ui.await(By.pkg(VisibleUi.APP).desc("New artwork"));
            ui.text(name);
            ui.await(By.pkg(VisibleUi.APP).desc("Actions"));
            JSONObject first = export("saved");
            ui.description("Close Export");
            ui.device.executeShellCommand("am force-stop " + VisibleUi.APP);
            assertTrue("The previous application process must be gone",
                    ui.device.executeShellCommand("pidof " + VisibleUi.APP).trim().isEmpty());
            launch();
            ui.await(By.pkg(VisibleUi.APP).desc("New artwork"));
            int secondPid = appPid();
            assertNotEquals("Reopen must use a new application process", firstPid, secondPid);
            ui.text(name);
            ui.await(By.pkg(VisibleUi.APP).desc("Actions"));
            JSONObject reopened = export("reopened");
            assertEquals(first.getString("pixelSha256"), reopened.getString("pixelSha256"));
            assertArrayEquals("All 4096 exported ARGB pixels must survive process death",
                    Files.readAllBytes(new File(output, "saved-pixels.argb").toPath()),
                    Files.readAllBytes(new File(output, "reopened-pixels.argb").toPath()));
            capture("reopened-export");
            JSONObject checks = new JSONObject();
            for (String key : new String[]{"productionProcess", "nonDebuggableRelease", "visibleUiCreatedArtwork",
                    "pointerPaintedNonblankPixels", "visibleUiSaved", "freshProcessReopened", "fileProviderGrantRead",
                    "allReopenedExportPixelsEqual"}) checks.put(key, true);
            JSONObject report = new JSONObject().put("schemaVersion", 1).put("token", token).put("revision", revision)
                    .put("appApkSha256", appHash).put("driverApkSha256", driverHash).put("success", true)
                    .put("driverPackage", context.getPackageName()).put("applicationPackage", VisibleUi.APP)
                    .put("driverPid", Process.myPid()).put("firstAppPid", firstPid).put("reopenedAppPid", secondPid)
                    .put("projectName", name).put("assertions", checks).put("publisherQualified", false);
            Files.write(new File(output, "workflow.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            try { capture("first-failure"); } catch (Throwable diagnostic) { failure.addSuppressed(diagnostic); }
            throw failure;
        }
    }

    private JSONObject export(String phase) throws Exception {
        ui.description("Actions");
        ui.text("Share");
        ui.text("Share or export artwork…");
        ui.text("PNG");
        ui.toggle("Flatten onto white", true, true);
        ui.field("File name (optional)", "release-" + phase);
        ui.text("Export PNG");
        ui.scroll(By.pkg(VisibleUi.APP).text("release-" + phase + ".png"));
        assertTrue(context.getSharedPreferences("receipt", Context.MODE_PRIVATE).edit()
                .putString("token", token).putString("phase", phase).commit());
        ui.text("Share");
        // The system chooser initially shows suggested apps; the receiver can be below them.
        // Wait for its actual scroll container, then expose and click the visible target there.
        String chooser = "com.android.intentresolver";
        BySelector chooserContent = By.pkg(chooser)
                .res(chooser + ":id/chooser_scrollable_container").scrollable(true);
        ui.await(chooserContent);
        ui.scroll(By.pkg(chooser).text("Qualification PNG receiver"), chooserContent, chooser).click();
        File receipt = new File(output, phase + "-receipt.json");
        File error = new File(output, phase + "-error.txt");
        long deadline = SystemClock.uptimeMillis() + VisibleUi.TIMEOUT;
        while (!receipt.isFile() && !error.isFile() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        if (error.isFile()) throw new AssertionError(readText(error));
        assertTrue("The actual chooser grant must deliver its PNG", receipt.isFile());
        ui.await(By.pkg(VisibleUi.APP).text("release-" + phase + ".png"));
        return new JSONObject(readText(receipt));
    }

    private void launch() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(VisibleUi.APP);
        assertNotNull("Production launcher entry must exist", intent);
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private static String readText(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private int appPid() throws Exception {
        String value = ui.device.executeShellCommand("pidof " + VisibleUi.APP).trim();
        assertTrue("Expected exactly one production process: " + value, value.matches("[0-9]+"));
        return Integer.parseInt(value);
    }

    private void capture(String label) throws Exception {
        assertTrue(ui.device.takeScreenshot(new File(output, label + ".png")));
        ui.device.dumpWindowHierarchy(new File(output, label + "-hierarchy.xml"));
    }

    private String required(String name, String pattern) {
        String value = arguments.getString(name);
        assertNotNull(name, value);
        assertTrue("Invalid " + name, value.matches(pattern));
        return value;
    }
}
