package io.github.capsopasme.assistant.root;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs as root through app_process, like {@link IconTool}:
 *
 *   CLASSPATH=&lt;apk&gt; app_process /system/bin io.github.capsopasme.assistant.root.MediaTool pause SELF_UID
 *
 * Some players keep playing when the assistant takes audio focus: they ignore a transient focus
 * loss, and the assistant's panel (a voice-interaction window) does not pause the activity under
 * it either. 红果短剧 is one. This finds who is still playing (root sees the real uid of every
 * player), and pauses exactly those apps' media sessions; "play" later resumes exactly those.
 * The generic media key is never used: it goes to the most recent session, which may be a
 * different app (and would start the music player instead).
 *
 *   pause SELF_UID     pause apps still playing media; prints paused=PKG / failed=PKG / none=1
 *   play PKG...        resume the given apps' sessions; prints ok=1
 *
 * Root holds MEDIA_CONTENT_CONTROL and MODIFY_AUDIO_ROUTING. Pure Java, reflection only: the hidden
 * interfaces are looked up by name, so it adapts to the running system.
 */
public final class MediaTool {
    private static final int USAGE_UNKNOWN = 0;
    private static final int USAGE_MEDIA = 1;
    private static final int USAGE_GAME = 14;
    private static final String CALLER = "com.android.shell";

    private MediaTool() {
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
        if (args.length >= 2 && args[0].equals("pause")) return pause(Integer.parseInt(args[1]));
        if (args.length >= 2 && args[0].equals("play")) {
            Set<String> pkgs = new LinkedHashSet<>();
            for (int i = 1; i < args.length; i++) pkgs.add(args[i]);
            for (Object c : controllers(pkgs)) {
                if (!invoke(c, "play", CALLER)) mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY);
            }
            System.out.println("ok=1");
            return 0;
        }
        System.out.println("error=usage: pause SELF_UID | play PKG...");
        return 1;
    }

    private static int pause(int selfUid) throws Exception {
        Set<Integer> uids = playingUids(selfUid);
        if (uids.isEmpty()) {
            System.out.println("none=1");
            return 0;
        }
        Object pm = service("package", "android.content.pm.IPackageManager");
        Method forUid = find(pm.getClass(), "getPackagesForUid", 1);
        for (int uid : uids) {
            String[] pkgs = (String[]) forUid.invoke(pm, uid);
            if (pkgs == null || pkgs.length == 0) continue;
            Set<String> set = new LinkedHashSet<>();
            for (String p : pkgs) set.add(p);
            List<Object> ctrls = controllers(set);
            if (ctrls.isEmpty()) {
                System.out.println("failed=" + pkgs[0] + " (no media session)");
                continue;
            }
            for (Object c : ctrls) invoke(c, "pause", CALLER);
            if (stoppedAfter(uid, 400)) {
                System.out.println("paused=" + pkgOf(ctrls.get(0), pkgs[0]));
                continue;
            }
            // the session ignored pause(): try the media button, delivered to this app's session only
            for (Object c : ctrls) mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
            if (stoppedAfter(uid, 400)) System.out.println("paused=" + pkgOf(ctrls.get(0), pkgs[0]));
            else System.out.println("failed=" + pkgs[0] + " (still playing)");
        }
        return 0;
    }

    /** uids (other than ours) with an active media / game / unknown-usage player */
    private static Set<Integer> playingUids(int selfUid) throws Exception {
        Object audio = service("audio", "android.media.IAudioService");
        List<?> configs = asList(find(audio.getClass(), "getActivePlaybackConfigurations", 0).invoke(audio));
        Set<Integer> uids = new LinkedHashSet<>();
        for (Object pc : configs) {
            if (!(Boolean) find(pc.getClass(), "isActive", 0).invoke(pc)) continue;
            Object attrs = find(pc.getClass(), "getAudioAttributes", 0).invoke(pc);
            int usage = (Integer) find(attrs.getClass(), "getUsage", 0).invoke(attrs);
            if (usage != USAGE_MEDIA && usage != USAGE_GAME && usage != USAGE_UNKNOWN) continue;
            int uid = (Integer) find(pc.getClass(), "getClientUid", 0).invoke(pc);
            if (uid >= 10000 && uid != selfUid) uids.add(uid);
        }
        return uids;
    }

    private static boolean stoppedAfter(int uid, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
            if (!playingUids(-1).contains(uid)) return true;
        }
        return false;
    }

    /** ISessionController binders of the given packages' sessions */
    private static List<Object> controllers(Set<String> pkgs) throws Exception {
        Object msm = service("media_session", "android.media.session.ISessionManager");
        Method get = null;
        for (Method m : msm.getClass().getMethods()) {
            if (m.getName().equals("getSessions") && m.getParameterTypes().length == 2) get = m;
        }
        if (get == null) throw new NoSuchMethodException("ISessionManager.getSessions");
        List<?> tokens = asList(get.invoke(msm, null, 0));
        Method asItf = Class.forName("android.media.session.ISessionController$Stub").getMethod("asInterface", IBinder.class);
        List<Object> out = new ArrayList<>();
        for (Object token : tokens) {
            // Token.getBinder() returns ISessionController on current systems, a raw IBinder on some
            Object o = find(token.getClass(), "getBinder", 0).invoke(token);
            Object c = o instanceof IBinder && !(o.getClass().getName().contains("ISessionController"))
                    ? asItf.invoke(null, o) : o;
            if (c == null) continue;
            String pkg = (String) find(c.getClass(), "getPackageName", 0).invoke(c);
            if (pkgs.contains(pkg)) out.add(c);
        }
        return out;
    }

    private static String pkgOf(Object controller, String fallback) {
        try {
            return (String) find(controller.getClass(), "getPackageName", 0).invoke(controller);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** pause(String) / play(String) on newer systems, no-argument versions on older ones */
    private static boolean invoke(Object c, String name, String caller) {
        try {
            Method m = findOrNull(c.getClass(), name, 1);
            if (m != null) m.invoke(c, caller);
            else find(c.getClass(), name, 0).invoke(c);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** sendMediaButton / dispatchMediaButton: parameters differ by version, filled by type */
    private static void mediaKey(Object c, int code) {
        Method m = null;
        for (Method x : c.getClass().getMethods()) {
            if (x.getName().equals("sendMediaButton") || x.getName().equals("dispatchMediaButton")) m = x;
        }
        if (m == null) return;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent ev = new KeyEvent(now, now, action, code, 0);
            Class<?>[] types = m.getParameterTypes();
            Object[] a = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class) a[i] = CALLER;
                else if (types[i] == KeyEvent.class) a[i] = ev;
                else if (types[i] == boolean.class) a[i] = Boolean.FALSE;
                else if (types[i] == int.class) a[i] = 0;
                else a[i] = null;
            }
            try {
                m.invoke(c, a);
            } catch (Exception ignored) {
            }
        }
    }

    private static Object service(String name, String itf) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, name);
        if (b == null) throw new IllegalStateException("no service " + name);
        return Class.forName(itf + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    private static List<?> asList(Object o) throws Exception {
        if (o == null) return new ArrayList<>();
        if (o instanceof List) return (List<?>) o;
        // ParceledListSlice on some versions
        return (List<?>) find(o.getClass(), "getList", 0).invoke(o);
    }

    private static Method findOrNull(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) return m;
        }
        return null;
    }

    private static Method find(Class<?> c, String name, int params) throws NoSuchMethodException {
        Method m = findOrNull(c, name, params);
        if (m == null) throw new NoSuchMethodException(c.getName() + "." + name + "/" + params);
        return m;
    }
}
