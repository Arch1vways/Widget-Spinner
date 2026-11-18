package dev.spinner;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/**
 * Ordinary home-screen widget, so it shows up in the launcher's widget list and behaves like any other widget
 * (place, resize, remove). Its RemoteViews are a static spinner; inside the launcher the LSPosed part
 * ({@link WidgetHooks}) swaps them for the live {@link SpinnerView}.
 */
public final class SpinnerWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, new RemoteViews(c.getPackageName(), R.layout.spinner_widget));
    }
}
