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
 * it either. This finds who is still playing (root sees the real uid of every player) and stops
 * exactly those, in the most cooperative way that works:
 *
 *  1. the app's media session, if it is a real one (it reports a playback state): pause() there,
 *     then the media button sent to that session only. The app knows it paused.
 *  2. otherwise, or if that didn't stop the sound, mute its players: every AudioTrack /
 *     MediaPlayer / AAudio stream is registered with the audio service together with a control
 *     interface (the one the system uses to enforce focus); set that player's volume to 0 there.
 *     红果短剧 needs this: its two sessions are empty shells (no state, no metadata) that ignore
 *     every command. Muting rather than pausing the player: a video engine runs on its own clock.
 *
 * Each step prints a resume token, and "resume" undoes exactly those: the same session, the same
 * player ids (never another player of the app, e.g. the next episode it preloaded and keeps paused).
 * The generic media key is never used: it goes to the most recent session, maybe another app.
 *
 *   pause SELF_UID   -> paused=PKG (how) | failed=PKG (why) | none=1, plus resume=TOKEN lines
 *   resume TOKEN...  -> s:PKG (session play) | m:PIID (unmute); p:PIID (player start, older); ok=1
 *
 * Root holds MEDIA_CONTENT_CONTROL and MODIFY_AUDIO_ROUTING (the latter is what makes the audio
 * service return the real uids and player interfaces). Pure Java, reflection only.
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
        if (args.length >= 1 && args[0].equals("resume")) {
            for (int i = 1; i < args.length; i++) resume(args[i]);
            System.out.println("ok=1");
            return 0;
        }
        System.out.println("error=usage: pause SELF_UID | resume TOKEN...");
        return 1;
    }

    // --- pause --------------------------------------------------------------------------------

    private static int pause(int selfUid) throws Exception {
        // started the moment the assistant took focus: give players that handle focus loss a beat
        // to pause by themselves (this process's own start-up already took most of it)
        SystemClock.sleep(150);
        Set<Integer> uids = playingUids(selfUid);
        if (uids.isEmpty()) {
            System.out.println("none=1");
            return 0;
        }
        Object pm = service("package", "android.content.pm.IPackageManager");
        Method forUid = find(pm.getClass(), "getPackagesForUid", 1);
        for (int uid : uids) {
            String[] pkgs = (String[]) forUid.invoke(pm, uid);
            String pkg = pkgs != null && pkgs.length > 0 ? pkgs[0] : "uid" + uid;
            Set<String> set = new LinkedHashSet<>();
            if (pkgs != null) for (String p : pkgs) set.add(p);

            // 1. a real media session (one that reports a playback state)
            List<Object> real = new ArrayList<>();
            for (Object c : controllers(set)) if (hasState(c)) real.add(c);
            if (!real.isEmpty()) {
                for (Object c : real) invoke(c, "pause", CALLER);
                boolean ok = stoppedAfter(uid, 400);
                if (!ok) {
                    for (Object c : real) mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
                    ok = stoppedAfter(uid, 300);
                }
                if (ok) {
                    System.out.println("resume=s:" + pkgOf(real.get(0), pkg));
                    System.out.println("paused=" + pkg + " (session)");
                    continue;
                }
            }

            // 2. no real session: mute the app's playing players (system-side volume of each
            //    player only). Not pause: a video engine keeps its own clock, so pausing just the
            //    audio leaves the picture running and freezes it on resume until the sound catches
            //    up (seen with 红果). Muted, sound and picture stay in step.
            List<Object> players = activePlayers(uid);
            int muted = 0;
            for (Object pc : players) {
                Object proxy = proxyOf(pc);
                Integer piid = piidOf(pc);
                if (proxy == null || piid == null) continue;
                if (call(proxy, "setVolume", 0f)) {
                    System.out.println("resume=m:" + piid);
                    muted++;
                }
            }
            if (muted > 0) System.out.println("paused=" + pkg + " (muted)");
            else System.out.println("failed=" + pkg + (players.isEmpty() ? " (no player interface)" : " (could not mute)"));
        }
        return 0;
    }

    // --- resume -------------------------------------------------------------------------------

    private static void resume(String token) throws Exception {
        if (token.length() < 3 || token.charAt(1) != ':') return;
        String v = token.substring(2);
        switch (token.charAt(0)) {
            case 's': {
                Set<String> set = new LinkedHashSet<>();
                set.add(v);
                for (Object c : controllers(set)) {
                    if (!invoke(c, "play", CALLER)) mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY);
                }
                break;
            }
            case 'p':
            case 'm': {
                int piid = Integer.parseInt(v);
                for (Object pc : allPlayers()) {
                    Integer id = piidOf(pc);
                    if (id == null || id != piid) continue;
                    Object proxy = proxyOf(pc);
                    if (proxy == null) continue;
                    if (token.charAt(0) == 'p') call(proxy, "start");
                    else call(proxy, "setVolume", 1f);
                }
                break;
            }
            default:
                break;
        }
    }

    // --- audio service ------------------------------------------------------------------------

    private static List<?> allPlayers() throws Exception {
        Object audio = service("audio", "android.media.IAudioService");
        return asList(find(audio.getClass(), "getActivePlaybackConfigurations", 0).invoke(audio));
    }

    private static boolean isMediaActive(Object pc) throws Exception {
        if (!(Boolean) find(pc.getClass(), "isActive", 0).invoke(pc)) return false;
        Object attrs = find(pc.getClass(), "getAudioAttributes", 0).invoke(pc);
        int usage = (Integer) find(attrs.getClass(), "getUsage", 0).invoke(attrs);
        return usage == USAGE_MEDIA || usage == USAGE_GAME || usage == USAGE_UNKNOWN;
    }

    private static int uidOf(Object pc) throws Exception {
        return (Integer) find(pc.getClass(), "getClientUid", 0).invoke(pc);
    }

    /** uids (other than ours) with an active media / game / unknown-usage player */
    private static Set<Integer> playingUids(int selfUid) throws Exception {
        Set<Integer> uids = new LinkedHashSet<>();
        for (Object pc : allPlayers()) {
            if (!isMediaActive(pc)) continue;
            int uid = uidOf(pc);
            if (uid >= 10000 && uid != selfUid) uids.add(uid);
        }
        return uids;
    }

    private static List<Object> activePlayers(int uid) throws Exception {
        List<Object> out = new ArrayList<>();
        for (Object pc : allPlayers()) if (isMediaActive(pc) && uidOf(pc) == uid) out.add(pc);
        return out;
    }

    private static Object proxyOf(Object pc) {
        try {
            return find(pc.getClass(), "getPlayerProxy", 0).invoke(pc);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer piidOf(Object pc) {
        try {
            return (Integer) find(pc.getClass(), "getPlayerInterfaceId", 0).invoke(pc);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean stoppedAfter(int uid, long ms) throws Exception {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
            if (!playingUids(-1).contains(uid)) return true;
        }
        return false;
    }

    // --- media sessions -----------------------------------------------------------------------

    /** ISessionController binders of the given packages' sessions */
    private static List<Object> controllers(Set<String> pkgs) throws Exception {
        List<Object> out = new ArrayList<>();
        if (pkgs.isEmpty()) return out;
        Object msm = service("media_session", "android.media.session.ISessionManager");
        Method get = null;
        for (Method m : msm.getClass().getMethods()) {
            if (m.getName().equals("getSessions") && m.getParameterTypes().length == 2) get = m;
        }
        if (get == null) throw new NoSuchMethodException("ISessionManager.getSessions");
        List<?> tokens = asList(get.invoke(msm, null, 0));
        Method asItf = Class.forName("android.media.session.ISessionController$Stub").getMethod("asInterface", IBinder.class);
        for (Object token : tokens) {
            // Token.getBinder() returns ISessionController on current systems, a raw IBinder on some
            Object o = find(token.getClass(), "getBinder", 0).invoke(token);
            Object c = o instanceof IBinder ? asItf.invoke(null, o) : o;
            if (c == null) continue;
            String pkg = (String) find(c.getClass(), "getPackageName", 0).invoke(c);
            if (pkgs.contains(pkg)) out.add(c);
        }
        return out;
    }

    /** a session that reports a playback state is a real one; null state = an empty shell */
    private static boolean hasState(Object c) {
        try {
            return find(c.getClass(), "getPlaybackState", 0).invoke(c) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String pkgOf(Object controller, String fallback) {
        try {
            return (String) find(controller.getClass(), "getPackageName", 0).invoke(controller);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * pause(String) / play(String) on newer systems, no-argument versions on older ones. The string
     * is the caller's package, informational in AOSP; should a ROM check it, retry as "android".
     */
    private static boolean invoke(Object c, String name, String caller) {
        for (String who : new String[]{caller, "android"}) {
            try {
                Method m = findOrNull(c.getClass(), name, 1);
                if (m != null) m.invoke(c, who);
                else find(c.getClass(), name, 0).invoke(c);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
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

    // --- reflection helpers -------------------------------------------------------------------

    /** a no-argument or one-float method (PlayerProxy.pause/start, setVolume(float)) */
    private static boolean call(Object o, String name, Object... args) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
                m.invoke(o, args);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
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
