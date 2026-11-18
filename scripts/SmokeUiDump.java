import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.util.concurrent.TimeoutException;

/** CI shell helper: read a fresh tree even when the connected timer never stays idle for one second. */
public final class SmokeUiDump {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("output width height required");
        File output = new File(args[0]);
        if (output.exists() && !output.delete()) throw new IllegalStateException("Cannot remove old tree");
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getConstructor().newInstance();
        wrapperClass.getMethod("connect").invoke(wrapper);
        try {
            UiAutomation automation = (UiAutomation) wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
            wrapperClass.getMethod("setCompressedLayoutHierarchy", boolean.class).invoke(wrapper, false);
            try { automation.waitForIdle(100, 1000); }
            catch (TimeoutException busy) { /* A changing timer must not prevent inspecting the active window. */ }
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; attempt < 10 && root == null; attempt++) {
                root = automation.getRootInActiveWindow();
                if (root == null) Thread.sleep(100);
            }
            if (root == null) throw new IllegalStateException("No active accessibility root");
            try {
                Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper")
                    .getMethod("dumpWindowToFile", AccessibilityNodeInfo.class, File.class, int.class, int.class, int.class)
                    .invoke(null, root, output, 0, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            } finally { root.recycle(); }
            if (!output.isFile() || output.length() == 0) throw new IllegalStateException("Tree was not written");
            System.out.println("UI hierarchy dumped to: " + args[0]);
        } finally { wrapperClass.getMethod("disconnect").invoke(wrapper); }
    }
}
