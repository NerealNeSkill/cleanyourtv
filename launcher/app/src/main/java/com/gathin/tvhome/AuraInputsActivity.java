package com.gathin.tvhome;

import static com.gathin.tvhome.AuraStyle.t;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AURA's signal sources: HDMI ports, AV and the tuner as cards in the AURA style. It opens a source the way the
 * classic Inputs menu does (the config's input_sources / input_action first, then the standard channel URI aimed at
 * the TV app), so the same TVs work, but never shows the classic home screen.
 */
public class AuraInputsActivity extends Activity {
    private AuraStyle.Palette pal;
    private Config cfg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = AuraStyle.palette(AuraStyle.prefs(this));
        cfg = Config.load(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(AuraStyle.background(pal));
        root.setPadding(dp(56), dp(36), dp(56), dp(24));
        TextView title = new TextView(this);
        title.setText(t("Источники сигнала", "Inputs"));
        title.setTextColor(pal.text);
        title.setTextSize(24);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(t("HDMI, AV и антенна", "HDMI, AV and tuner"));
        sub.setTextColor(pal.sub);
        sub.setTextSize(13);
        sub.setPadding(0, dp(2), 0, dp(18));
        root.addView(sub);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int count = fill(grid);
        if (count == 0) {
            TextView none = new TextView(this);
            none.setText(t("Этот ТВ не сообщает источники сигнала", "This TV reports no inputs"));
            none.setTextColor(pal.sub);
            none.setTextSize(15);
            root.addView(none);
        }
        setContentView(root);
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int fill(GridLayout grid) {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return 0;
        List<TvInputInfo> all = new ArrayList<>(tim.getTvInputList());
        Set<String> parents = new HashSet<>();
        for (TvInputInfo i : all) if (i.getParentId() != null && i.isPassthroughInput() && !i.isHidden(this)) parents.add(i.getParentId());
        List<TvInputInfo> shown = new ArrayList<>();
        boolean tuner = false;
        for (TvInputInfo i : all) {
            if (i.isHidden(this)) continue;
            if (i.getType() == TvInputInfo.TYPE_TUNER) {
                if (tuner || (i.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                tuner = true;
                shown.add(i);
                continue;
            }
            if (!i.isPassthroughInput() || parents.contains(i.getId())) continue;
            if (Integer.valueOf(-1).equals(InputSources.mapped(cfg.inputSources, i.getId()))) continue;
            shown.add(i);
        }
        shown.sort((a, c) -> {
            boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hc = c.getType() == TvInputInfo.TYPE_HDMI;
            if (ha != hc) return ha ? -1 : 1;
            return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(c.loadLabel(this)));
        });
        for (TvInputInfo i : shown) grid.addView(card(tim, i), cellParams());
        return shown.size();
    }

    private GridLayout.LayoutParams cellParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(250);
        lp.height = dp(96);
        lp.setMargins(0, 0, dp(14), dp(14));
        return lp;
    }

    private LinearLayout card(TvInputManager tim, final TvInputInfo info) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(18), dp(10), dp(18), dp(10));
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(AuraStyle.card(pal, dp(16), false));
        c.setOnFocusChangeListener((v, f) -> v.setBackground(AuraStyle.card(pal, dp(16), f)));
        CharSequence raw = info.loadCustomLabel(this);
        String custom = InputNames.tidy(raw);
        String label = String.valueOf(info.loadLabel(this));
        TextView name = new TextView(this);
        name.setText(custom != null && !custom.equals(label) ? custom + " · " + label : label);
        name.setTextColor(pal.text);
        name.setTextSize(17);
        name.setSingleLine(true);
        c.addView(name);
        TextView state = new TextView(this);
        boolean tuner = info.getType() == TvInputInfo.TYPE_TUNER;
        boolean connected = false;
        try { connected = tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED; } catch (RuntimeException ignored) { }
        state.setText(tuner ? t("Эфирное и кабельное ТВ", "Live TV") : connected ? t("Подключено", "Connected") : t("Нет сигнала", "No signal"));
        state.setTextColor(connected || tuner ? pal.accent : pal.sub);
        state.setTextSize(12);
        c.addView(state);
        c.setOnClickListener(v -> { open(info); finish(); });
        return c;
    }

    // ------------------------------------------------------------------ open an input

    private void start(Intent i) throws ActivityNotFoundException, SecurityException {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private Intent actionIntent() {
        ComponentName component = InputSources.isComponent(cfg.inputAction) ? ComponentName.unflattenFromString(cfg.inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(cfg.inputAction);
    }

    private void open(TvInputInfo info) {
        String inputId = info.getId();
        if (info.getType() == TvInputInfo.TYPE_TUNER) {
            try {
                Uri uri = TvContract.Channels.CONTENT_URI;
                if (!cfg.liveTvPackage.isEmpty()) {
                    try { start(new Intent(Intent.ACTION_VIEW, uri).setPackage(cfg.liveTvPackage)); return; } catch (ActivityNotFoundException | SecurityException ignored) { }
                    Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(cfg.liveTvPackage);
                    if (launch != null) { start(launch); return; }
                }
                start(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String sourceId = info.getParentId() != null ? info.getParentId() : inputId;
        Integer source = InputSources.mapped(cfg.inputSources, sourceId);
        try {
            if (source != null && source >= 0 && !cfg.inputAction.isEmpty()) {
                start(actionIntent().putExtra("input", source.intValue()));
                return;
            } else if (source == null && InputSources.isComponent(cfg.inputAction)) {
                start(actionIntent());
                return;
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // fall through to the standard way
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = cfg.inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(cfg.inputUriScheme).build());
        if (!cfg.liveTvPackage.isEmpty()) {
            try { start(new Intent(view).setPackage(cfg.liveTvPackage)); return; } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        try {
            start(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (cfg.inputAction.isEmpty()) {
                Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show();
                return;
            }
            try { start(actionIntent().setDataAndType(uri, "vnd.android.cursor.item/channel")); } catch (ActivityNotFoundException | SecurityException e2) {
                Toast.makeText(this, t("Не удалось открыть", "Cannot open"), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
