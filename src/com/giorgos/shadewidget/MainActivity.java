package com.giorgos.shadewidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: grant notification access, then add the widget. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ((Button) findViewById(R.id.btn_access)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessSettings(); }
        });
        ((Button) findViewById(R.id.btn_appinfo)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
        });
        ((Button) findViewById(R.id.btn_widget)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pinWidget(); }
        });

        Switch dismiss = findViewById(R.id.switch_dismiss);
        dismiss.setChecked(WidgetRenderer.isDismissEnabled(this));
        dismiss.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                WidgetRenderer.setDismissEnabled(MainActivity.this, on);
                NotifListener.requestRender(MainActivity.this, true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean granted = WidgetRenderer.hasAccess(this);
        ((TextView) findViewById(R.id.status)).setText(granted
                ? "✓ Notification access is on"
                : "Notification access is off");
        if (granted) NotifListener.ensureConnected(this);
        NotifListener.requestRender(this, true);
    }

    private void openAccessSettings() {
        ComponentName cn = new ComponentName(this, NotifListener.class);
        Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString());
        try {
            startActivity(detail);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void pinWidget() {
        AppWidgetManager awm = getSystemService(AppWidgetManager.class);
        if (awm != null && awm.isRequestPinAppWidgetSupported()) {
            awm.requestPinAppWidget(new ComponentName(this, ShadeWidgetProvider.class), null, null);
        } else {
            Toast.makeText(this, "Long-press the home screen, tap Widgets, and find Shade Widget.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
