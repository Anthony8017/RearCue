// ShuidOverlayProbe.java -- ticket #17 one-shot probe: can a SHELL-UID (2000) process add a
// TYPE_APPLICATION_OVERLAY window onto the rear display, i.e. does the HyperOS rear-display
// window policy ("Not allow non-system app ... add system_window on rear display", ticket #10
// / E9) treat shell uid as a system app?
//
// Run on the device with app_process (the process IS shell uid 2000, the same uid identity a
// Shizuku UserService runs with -- the equivalence boundary is recorded in the findings):
//
//   app_process -Djava.class.path=/data/local/tmp/shuid-overlay.dex /system/bin \
//       ShuidOverlayProbe <displayId> <add|remove> [holdSeconds] [package] [tryRegister]
//
//   add     add the minimal probe window to <displayId>, hold it for [holdSeconds] (default 6,
//           looper pumping so the window really draws), then removeView and exit
//   remove  one-shot add -> remove cycle on <displayId> (the ticket's "remove" step: a window
//           can only be removed by the process that added it, so removal is exercised as an
//           add-then-remove cycle inside one process)
//   package window attribution: the context package the window carries (default com.rearcue.poc
//           -- same attribution as the ticket #10 E9 probe). "system" = bare system context.
//   tryRegister  "on" = after the window attempts, also try the app attach handshake
//           (ActivityThread.attach(false) -> IActivityManager.attachApplication). LAST step on
//           purpose: on this build the handshake gets an unregistered shell process KILLED.
//
// Window glue strategy chain (each attempt is reported as a fact, first success wins):
//   window-context   Context.createWindowContext(display, TYPE_APPLICATION_OVERLAY, null) --
//                    the exact glue of the ticket #10 E9 probe
//   display-context  Context.createDisplayContext(display) + its WindowManager -- same display
//                    targeting, but it skips WMS's window-context attach. On this build the
//                    window-context path refuses processes that ActivityTaskManager does not
//                    know (an app_process process is one):
//                      WindowManager: attachWindowContextToDisplayArea: calling from
//                        non-existing process pid=<pid> uid=2000
//                      WindowManager: Window Manager Crash java.lang.IllegalStateException:
//                        Unknown pid=<pid> uid=2000
//   plain            the base context WindowManager (lands on the default display -- only
//                    meaningful for the display-0 control)
//
// The probe JUDGES NOTHING. It prints machine-readable facts to stdout ("probe: <verb> k=v ...")
// and the verdict comes from `dumpsys window windows` + system-side WindowManager log lines +
// these lines (docs/poc-findings.md, ticket #17). Exit codes are never evidence.
//
// ASCII-only source, like every file under tools/ex (Windows PowerShell 5.1 reads BOM-less
// sources as ANSI/GBK; the device text is data, never source).

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Method;

public final class ShuidOverlayProbe {

    /** `dumpsys window windows` looks for this title (tools/ex parsing seam keys on it). */
    public static final String TITLE = "RearCueShUidProbe";

    private static final String DEFAULT_PACKAGE = "com.rearcue.poc";

    private static boolean sAdded = false;
    private static boolean sRemoved = false;

    private ShuidOverlayProbe() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            selfFail("uncaught reason=" + describe(t));
        } finally {
            // Binder threads can keep the VM alive after `done` is printed (observed on this
            // build: the process stayed around long after the run ended) -- exit explicitly so
            // no probe process (or window) can linger into a later phase.
            System.exit(0);
        }
    }

    private static void run(String[] args) {
        int displayId = parseInt(args.length > 0 ? args[0] : "", -1);
        String mode = args.length > 1 ? args[1] : "";
        int holdSeconds = parseInt(args.length > 2 ? args[2] : "6", 6);
        String pkg = args.length > 3 ? args[3] : DEFAULT_PACKAGE;
        String tryRegister = args.length > 4 ? args[4] : "off";

        line("start", "mode=" + mode + " display=" + displayId + " pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid() + " title=" + TITLE
                + " hold=" + holdSeconds + " pkg=" + pkg);

        if (!"add".equals(mode) && !"remove".equals(mode)) {
            selfFail("unknown mode: " + mode);
            done(mode);
            return;
        }

        try {
            // The main thread of app_process has no looper, and every Handler (including the
            // ones inside ActivityThread / ViewRootImpl) needs one.
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }

            Context base = createBaseContext(pkg);
            if (base == null) {
                done(mode);
                return;
            }

            DisplayManager displayManager = base.getSystemService(DisplayManager.class);
            Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
            if (display == null) {
                line("add", "failed reason=no-display requested=" + displayId);
                done(mode);
                return;
            }

            String[] strategies = {"window-context", "display-context", "plain"};
            String lastFailure = null;
            for (int i = 0; i < strategies.length; i++) {
                String strategy = strategies[i];
                boolean last = (i == strategies.length - 1);
                try {
                    runWindowCycle(base, display, strategy, mode, holdSeconds);
                    return;
                } catch (Throwable t) {
                    lastFailure = describe(t);
                    line("attempt", "strategy=" + strategy + " failed reason=" + lastFailure);
                }
            }
            line("add", "failed reason=" + lastFailure);
        } catch (Throwable t) {
            selfFail(describe(t));
        }

        // Optional LAST step (`<tryRegister>` = on): try the regular app attach handshake
        // (ActivityThread.attach(false) -> IActivityManager.attachApplication) so the process
        // would become one ActivityTaskManager knows. On this build that handshake gets an
        // unregistered shell process KILLED outright (SIGKILL), so it must never run before the
        // window attempts -- and its own outcome is a printed fact, never a verdict.
        if ("on".equals(tryRegister)) {
            line("register", "attempt method=attachApplication");
            tryRegisterProcess();
        }
        done(mode);
    }

    /**
     * One full window cycle on one glue strategy: build the WindowManager for the requested
     * display, addView, hold, removeView. Throws on any failure so the caller can fall through
     * to the next strategy; prints `probe: add ok ... strategy=<s>` on success. `done` is printed
     * here (after the clean removal) so the phase has a well-defined end marker.
     */
    private static void runWindowCycle(Context base, Display display, String strategy,
                                       String mode, int holdSeconds) throws Exception {
        Context windowContext;
        final WindowManager windowManager;
        if ("window-context".equals(strategy)) {
            // This call IS `WindowManager: attachWindowContextToDisplayArea` (exact system-side
            // name -- see the header): the context hands WMS a displayAreaToken that ANDROID
            // builds (the null options bundle here), never a token this probe constructs. The
            // whole window-context strategy dies inside that call on an unregistered process
            // (`calling from non-existing process pid=... uid=2000`); the facts below say which
            // of the two happened -- this comment pins the seam so an Android API rename or a
            // token-semantics change is noticeable at the call site.
            windowContext = base.createWindowContext(
                    display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            line("attempt", "strategy=window-context context-ok type="
                    + WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            windowManager = windowContext.getSystemService(WindowManager.class);
        } else if ("display-context".equals(strategy)) {
            windowContext = base.createDisplayContext(display);
            line("attempt", "strategy=display-context context-ok display=" + display.getDisplayId());
            windowManager = windowContext.getSystemService(WindowManager.class);
        } else {
            windowContext = base;
            line("attempt", "strategy=plain context-ok");
            windowManager = windowContext.getSystemService(WindowManager.class);
        }

        final View view = new View(windowContext);
        view.setBackgroundColor(0xC0202020);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                320,
                160,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.RGBA_8888);
        params.setTitle(TITLE);
        params.gravity = Gravity.CENTER;

        try {
            windowManager.addView(view, params);
        } catch (Throwable t) {
            // BadTokenException / SecurityException / policy refusals land here; the class name
            // + message is the probe-side failure reason the verdict quotes as a fact. The
            // caller prints the `attempt failed` line (one line per failed attempt).
            throw (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
        }
        sAdded = true;
        line("add", "ok display=" + display.getDisplayId() + " title=" + TITLE + " strategy=" + strategy);

        final Handler handler = new Handler(Looper.myLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    windowManager.removeView(view);
                    sRemoved = true;
                    line("remove", "ok");
                } catch (Throwable t) {
                    line("remove", "failed reason=" + describe(t));
                } finally {
                    Looper.myLooper().quitSafely();
                }
            }
        }, (long) (mode.equals("add") ? holdSeconds : 1) * 1000L);

        Looper.loop();
        done(mode);
    }

    /**
     * Best-effort "become a process ActivityTaskManager knows": run the regular app attach
     * handshake (ActivityThread.attach(false) -> IActivityManager.attachApplication) through
     * reflection, in a worker thread with a 3s budget -- on this build the handshake never
     * returns for a process AMS did not start, so it must not stall the probe. Reported as
     * `probe: register ok|failed|timeout ...` -- the outcome is a fact, never a verdict.
     */
    private static void tryRegisterProcess() {
        final String[] result = new String[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                    Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
                    currentActivityThread.setAccessible(true);
                    Object activityThread = currentActivityThread.invoke(null);
                    if (activityThread == null) {
                        result[0] = "skip reason=no-current-activity-thread";
                        return;
                    }
                    Method attach = activityThreadClass.getDeclaredMethod("attach", boolean.class, long.class);
                    attach.setAccessible(true);
                    attach.invoke(activityThread, false, 0L);
                    result[0] = "ok method=attachApplication";
                } catch (Throwable t) {
                    Throwable cause = t;
                    if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
                        cause = t.getCause();
                    }
                    result[0] = "failed reason=" + describe(cause);
                }
            }
        }, "shuid-register");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            // fall through to the report below
        }
        if (worker.isAlive()) {
            line("register", "timeout after 3000ms (attach handshake did not return; thread left running)");
        } else if (result[0] != null) {
            line("register", result[0]);
        } else {
            line("register", "failed reason=no-result");
        }
    }

    /**
     * Base context for the window. Reflection on ActivityThread.systemMain()/getSystemContext()
     * is the standard app_process bootstrap (app_process has no Application/Activity to take a
     * Context from). `package` becomes the requested window attribution (same package as the
     * ticket #10 E9 probe). Returns null (and prints the failure) when the bootstrap fails --
     * that is probe self-failure, never a verdict on the rear-display policy.
     */
    private static Context createBaseContext(String pkg) {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread = systemMain.invoke(null);
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context system = (Context) getSystemContext.invoke(activityThread);
            if (system == null) {
                selfFail("getSystemContext returned null");
                return null;
            }
            if ("system".equals(pkg)) {
                line("context", "ok source=system-context opPackage=" + system.getOpPackageName());
                return system;
            }
            Context app = system.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
            line("context", "ok source=package:" + pkg + " opPackage=" + app.getOpPackageName());
            return app;
        } catch (Throwable t) {
            selfFail("context bootstrap failed reason=" + describe(t));
            return null;
        }
    }

    private static void selfFail(String reason) {
        line("self-fail", "reason=" + reason);
    }

    private static void done(String mode) {
        line("done", "mode=" + mode + " added=" + sAdded + " removed=" + sRemoved);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : (": " + message));
    }

    private static void line(String verb, String body) {
        System.out.println("probe: " + verb + " " + body);
        System.out.flush();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
