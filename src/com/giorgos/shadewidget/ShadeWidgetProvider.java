package com.giorgos.shadewidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

public class ShadeWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        NotifListener.ensureConnected(context);
        NotifListener.requestRender(context, true);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        NotifListener.requestRender(context, true);
    }

    @Override
    public void onEnabled(Context context) {
        NotifListener.ensureConnected(context);
        NotifListener.requestRender(context, true);
    }
}
