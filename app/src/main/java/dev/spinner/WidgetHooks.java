package dev.spinner;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Runs inside the launcher. Every AppWidgetHostView that hosts our provider gets a live {@link SpinnerView}
 * instead of the inflated RemoteViews. Only the framework class AppWidgetHostView is hooked, so the launcher's
 * own code is untouched and other widgets are not affected.
 */
final class WidgetHooks {
    static final String TAG = "SpinnerModule";
    static final String PROVIDER_PKG = "arch1.dev.spinner";
    private static final String VIEW_TAG = "dev.spinner.live";

    private WidgetHooks() { }

    static void install() {
        // our host keeps its live view: the RemoteViews inflation is skipped
        Hooker keepLive = chain -> {
            boolean skip = false;
            try {
                Object o = chain.getThisObject();
                if (o instanceof AppWidgetHostView && isOurs((AppWidgetHostView) o)) {
                    ensureLive((AppWidgetHostView) o);
                    skip = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "keepLive", t);
            }
            return skip ? null : chain.proceed();
        };
        Hooker afterBind = chain -> {
            Object result = chain.proceed();
            try {
                Object o = chain.getThisObject();
                if (o instanceof AppWidgetHostView && isOurs((AppWidgetHostView) o)) ensureLive((AppWidgetHostView) o);
            } catch (Throwable t) {
                Log.e(TAG, "setAppWidget", t);
            }
            return result;
        };
        for (String m : new String[] {"updateAppWidget", "applyRemoteViews", "reapplyLastRemoteViews"}) {
            try {
                HookEntry.interceptMethods(AppWidgetHostView.class, m, keepLive);
            } catch (Throwable t) {
                Log.w(TAG, "hook " + m, t);
            }
        }
        try {
            HookEntry.interceptMethods(AppWidgetHostView.class, "setAppWidget", afterBind);
        } catch (Throwable t) {
            Log.w(TAG, "hook setAppWidget", t);
        }
    }

    static boolean isOurs(AppWidgetHostView host) {
        AppWidgetProviderInfo info = host.getAppWidgetInfo();
        return info != null && info.provider != null && PROVIDER_PKG.equals(info.provider.getPackageName());
    }

    static void ensureLive(AppWidgetHostView host) {
        View v = host.findViewWithTag(VIEW_TAG);
        if (v != null && v.getParent() == host && host.getChildCount() == 1) return;
        host.removeAllViews();
        // previews in the widget picker use an invalid id: draw them, but leave touches to the picker
        boolean interactive = host.getAppWidgetId() > 0;
        SpinnerView s = new SpinnerView(host.getContext(), interactive);
        s.setTag(VIEW_TAG);
        host.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
