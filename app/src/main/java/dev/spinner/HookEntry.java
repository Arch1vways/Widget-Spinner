package dev.spinner;

import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;

/** LSPosed entry point (libxposed API 102): only the vivo launcher (com.bbk.launcher2) is touched. */
public final class HookEntry extends XposedModule {
    private static final String LAUNCHER = "com.bbk.launcher2";
    private static final AtomicBoolean HOOKED = new AtomicBoolean();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        xposed = this;
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!LAUNCHER.equals(param.getPackageName())) return;
        if (!HOOKED.compareAndSet(false, true)) return;
        try {
            WidgetHooks.install();
        } catch (Throwable t) {
            Log.e(WidgetHooks.TAG, "install", t);   // never break the launcher
        }
    }

    // ------------------------------------------------------------------ libxposed API 102 hooks

    private static final String HOOK_TAG = "SpinnerModule";

    /** Framework interface of the loaded module, set in onModuleLoaded. */
    static volatile XposedInterface xposed;

    /** Intercepts one method or constructor: {@code hook(target).intercept(hooker)}. */
    static HookHandle intercept(Executable target, Hooker hooker) {
        XposedInterface x = xposed;
        if (x == null) throw new IllegalStateException("Xposed module is not loaded");
        return x.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    /** Every non-abstract method with this name declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptMethods(Class<?> type, String name, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) targets.add(m);
        }
        return interceptAll(targets, hooker);
    }

    /** Every constructor declared by {@code type}; fails only if none could be hooked. */
    static List<HookHandle> interceptConstructors(Class<?> type, Hooker hooker) {
        List<Executable> targets = new ArrayList<>();
        for (Constructor<?> c : type.getDeclaredConstructors()) targets.add(c);
        return interceptAll(targets, hooker);
    }

    private static List<HookHandle> interceptAll(List<Executable> targets, Hooker hooker) {
        List<HookHandle> out = new ArrayList<>(targets.size());
        Throwable failure = null;
        for (Executable e : targets) {
            try {
                out.add(intercept(e, hooker));
            } catch (Throwable t) {
                failure = t;
                hookError("hook " + e, t);
            }
        }
        if (out.isEmpty() && failure != null) {
            if (failure instanceof Error) throw (Error) failure;
            throw failure instanceof RuntimeException ? (RuntimeException) failure : new RuntimeException(failure);
        }
        return out;
    }

    private static void hookError(String what, Throwable t) {
        XposedInterface x = xposed;
        if (x != null) x.log(Log.ERROR, HOOK_TAG, what, t);
        else Log.e(HOOK_TAG, what, t);
    }
}
