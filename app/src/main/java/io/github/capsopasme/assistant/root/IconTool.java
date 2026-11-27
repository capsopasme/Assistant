package io.github.capsopasme.assistant.root;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs as root through app_process, not inside the app:
 *
 *   CLASSPATH=&lt;apk&gt; app_process /system/bin io.github.capsopasme.assistant.root.IconTool resolve
 *
 * It talks to the status bar service with the framework's own IStatusBarService stub, so the
 * method signatures and binder transaction codes are always the ones of the system actually
 * running (HyperOS adds methods of its own, which shifts the numbers). Root holds STATUS_BAR.
 *
 *   resolve                    print the transaction codes ChgCtl needs (key=value lines)
 *   show SLOT PKG ID [DESC]    put an icon in the status bar (used by the self-test)
 *   hide SLOT                  remove it again
 *
 * Exit code 0 = done; anything else = failed, with a line "error=..." on stdout.
 * Pure Java on purpose: nothing from the app is loaded, no Context is needed.
 */
public final class IconTool {
    private static final String ITF = "com.android.internal.statusbar.IStatusBarService";

    private IconTool() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("error=" + c.getClass().getSimpleName() + ": " + c.getMessage());
            code = 2;
        }
        System.out.flush();
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("error=usage: resolve | show SLOT PKG ID [DESC] | hide SLOT");
            return 1;
        }
        Class<?> itf = Class.forName(ITF);
        Class<?> stub = Class.forName(ITF + "$Stub");
        Method setIcon = itf.getMethod("setIcon", String.class, String.class, int.class, int.class, String.class);
        Method setVis = itf.getMethod("setIconVisibility", String.class, boolean.class);
        Method remove = itf.getMethod("removeIcon", String.class);

        switch (args[0]) {
            case "resolve": {
                System.out.println("set_icon=" + code(stub, "setIcon"));
                System.out.println("set_vis=" + code(stub, "setIconVisibility"));
                System.out.println("remove_icon=" + code(stub, "removeIcon"));
                System.out.println("sdk=" + android.os.Build.VERSION.SDK_INT);
                return 0;
            }
            case "show": {
                if (args.length < 4) {
                    System.out.println("error=usage: show SLOT PKG ID [DESC]");
                    return 1;
                }
                Object svc = service(stub);
                String desc = args.length > 4 ? args[4] : "";
                setIcon.invoke(svc, args[1], args[2], Integer.parseInt(args[3]), 0, desc);
                setVis.invoke(svc, args[1], true);
                System.out.println("ok=1");
                return 0;
            }
            case "hide": {
                if (args.length < 2) {
                    System.out.println("error=usage: hide SLOT");
                    return 1;
                }
                remove.invoke(service(stub), args[1]);
                System.out.println("ok=1");
                return 0;
            }
            default:
                System.out.println("error=unknown command " + args[0]);
                return 1;
        }
    }

    private static Object service(Class<?> stub) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "statusbar");
        if (b == null) throw new IllegalStateException("statusbar service not found");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static int code(Class<?> stub, String method) throws Exception {
        Field f = stub.getDeclaredField("TRANSACTION_" + method);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
