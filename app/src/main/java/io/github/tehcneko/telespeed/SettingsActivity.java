package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.View;

import androidx.annotation.NonNull;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment {
        private XposedService service;
        private Preference speed;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName("conf");
            addPreferencesFromResource(R.xml.prefs);

            speed = findPreference("speed");
            speed.setEnabled(false);
            speed.setOnPreferenceChangeListener((preference, newValue) -> {
                if (service != null) {
                    SharedPreferences preferences = service.getRemotePreferences("conf");
                    preferences.edit().putString("speed", (String) newValue).apply();
                    return true;
                }
                return false;
            });
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    SettingsFragment.this.service = service;
                    speed.setEnabled(true);
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    SettingsFragment.this.service = null;
                    speed.setEnabled(false);
                }
            });
            new Handler().postDelayed(() -> {
                if (service == null) {
                    speed.setSummary(R.string.not_supported);
                }
            }, 1000);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            View list = view.findViewById(android.R.id.list);
            list.setOnApplyWindowInsetsListener((v, insets) -> {
                list.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getStableInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            super.onViewCreated(view, savedInstanceState);
        }
    }
}