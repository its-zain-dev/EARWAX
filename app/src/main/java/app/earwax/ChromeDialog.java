package app.earwax;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/**
 * EARWAX 4.0 Redesigned Early-2000s Y2K Liquid-Chrome & Glossy Aqua Modal Popups.
 * Features:
 *  - Metallic brushed-chrome multi-stop outer chassis bezel + inner glowing sapphire glass cavity
 *  - Classic early-2000s glossy Aqua specular header strip
 *  - Tactile 3D candy-gel Aqua pill buttons with bright top meniscus reflections
 *  - Instant Search History & quick suggestion chips inside search popups!
 */
final class ChromeDialog {
    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Multi-layer Y2K Liquid-Chrome framed obsidian glass dialog panel. */
    private static LayerDrawable chromeFrameBg(Context c) {
        // Outer liquid-mercury metallic bezel
        GradientDrawable outer = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xffeaf5ff, 0xff7089a8, 0xffd2e5fb, 0xff283952, 0xff8fa9c8});
        outer.setCornerRadius(dp(c, 24));
        outer.setStroke(dp(c, 1.2f), Theme.alpha(Theme.primary(), 200));

        // Inner deep glossy sapphire-obsidian glass well
        GradientDrawable inner = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf710223d, 0xf7071122, 0xf7040a16});
        inner.setCornerRadius(dp(c, 21));
        inner.setStroke(dp(c, 1f), Theme.alpha(Theme.primary(), 110));

        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{outer, inner});
        int inset = dp(c, 3.2f);
        ld.setLayerInset(1, inset, inset, inset, inset);
        return ld;
    }

    /** Glossy Y2K Aqua candy-pill button or recessed dark sapphire-chrome pill. */
    private static GradientDrawable aquaButtonBg(Context c, boolean primary, float radDp) {
        GradientDrawable d;
        if (primary) {
            int p = Theme.primary();
            int hi = Theme.lerp(p, 0xffffffff, 0.50f);
            int lo = Theme.lerp(p, 0xff051428, 0.35f);
            d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{hi, p, lo});
            d.setStroke(dp(c, 1.3f), Theme.glow());
        } else {
            d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff172a46, 0xff0c182c, 0xff091324});
            d.setStroke(dp(c, 1.1f), Theme.alpha(Theme.CHROME_MID, 145));
        }
        d.setCornerRadius(dp(c, radDp));
        return d;
    }

    private static GradientDrawable fieldBg(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff040a14, 0xff081326});
        d.setCornerRadius(dp(c, 14));
        d.setStroke(dp(c, 1.4f), Theme.alpha(Theme.primary(), 165));
        return d;
    }

    private static Dialog baseDialog(Context c, View content) {
        Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(content);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.90f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.74f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dlg;
    }

    private static LinearLayout container(Context c, String title, String subtitle) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(chromeFrameBg(c));
        int pad = dp(c, 4);
        outer.setPadding(pad, pad, pad, pad);

        // Glossy Y2K Chrome Title Header Strip
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hdrBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.alpha(Theme.primary(), 72), Theme.alpha(Theme.secondary(), 28), 0x00000000});
        float r = dp(c, 20);
        hdrBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        header.setBackground(hdrBg);
        header.setPadding(dp(c, 18), dp(c, 15), dp(c, 18), dp(c, 10));

        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(Theme.TEXT_MAIN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTypeface(Theme.TF_BOLD);
        tv.setShadowLayer(8f, 0f, 0f, Theme.alpha(Theme.primary(), 140));
        header.addView(tv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(c);
            sub.setText(subtitle);
            sub.setTextColor(Theme.glow());
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f);
            sub.setTypeface(Theme.TF_MEDIUM);
            sub.setPadding(0, dp(c, 3), 0, 0);
            header.addView(sub);
        }
        outer.addView(header);

        // Hairline chrome divider under header
        View div = new View(c);
        div.setBackgroundColor(Theme.alpha(Theme.primary(), 90));
        outer.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));

        // Body container
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 16));
        outer.addView(body);
        outer.setTag(body);
        return outer;
    }

    private static TextView pillButton(Context c, String label, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTypeface(Theme.TF_BOLD);
        b.setTextColor(primary ? 0xff03101e : Theme.TEXT_MAIN);
        b.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        b.setBackground(aquaButtonBg(c, primary, 16));
        b.setOnClickListener(click);
        return b;
    }

    static void input(Context c, String title, String hint, String initial, int inputType, Consumer<String> onOk) {
        inputWithChips(c, title, null, hint, initial, inputType, null, onOk);
    }

    static void inputWithChips(Context c, String title, String subtitle, String hint, String initial,
                               int inputType, List<String> suggestions, Consumer<String> onOk) {
        LinearLayout outer = container(c, title, subtitle);
        LinearLayout body = (LinearLayout) outer.getTag();
        final Dialog dlg = baseDialog(c, outer);

        EditText et = new EditText(c);
        et.setSingleLine(true);
        et.setHint(hint);
        if (initial != null) {
            et.setText(initial);
            et.setSelection(initial.length());
        }
        et.setInputType(inputType);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.setTextColor(Theme.TEXT_MAIN);
        et.setHintTextColor(Theme.TEXT_MUTED);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        et.setBackground(fieldBg(c));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(c, 12);
        body.addView(et, elp);

        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String val = et.getText().toString().trim();
                dlg.dismiss();
                onOk.accept(val);
                return true;
            }
            return false;
        });

        // Optional recent search / quick Y2K Aqua suggestion chips!
        if (suggestions != null && !suggestions.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(c);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout chipRow = new LinearLayout(c);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            for (String s : suggestions) {
                TextView ch = new TextView(c);
                ch.setText(s);
                ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                ch.setTypeface(Theme.TF_BOLD);
                ch.setTextColor(Theme.glow());
                ch.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
                ch.setBackground(aquaButtonBg(c, false, 14));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(c, 7);
                ch.setOnClickListener(v -> {
                    dlg.dismiss();
                    onOk.accept(s);
                });
                chipRow.addView(ch, lp);
            }
            hsv.addView(chipRow);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.bottomMargin = dp(c, 14);
            body.addView(hsv, hlp);
        }

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        TextView cancel = pillButton(c, c.getString(R.string.dlg_cancel), false, v -> dlg.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(c, 10);
        row.addView(cancel, clp);

        TextView ok = pillButton(c, c.getString(R.string.dlg_ok), true, v -> {
            String val = et.getText().toString().trim();
            dlg.dismiss();
            onOk.accept(val);
        });
        row.addView(ok);
        body.addView(row);

        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dlg.show();
        et.requestFocus();
    }

    static void actions(Context c, String title, String subtitle, String[] labels, Consumer<Integer> onPick) {
        LinearLayout outer = container(c, title, subtitle);
        LinearLayout body = (LinearLayout) outer.getTag();
        final Dialog dlg = baseDialog(c, outer);

        ScrollView sv = new ScrollView(c);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView row = new TextView(c);
            row.setText(labels[i]);
            row.setTextColor(Theme.TEXT_MAIN);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.2f);
            row.setTypeface(Theme.TF_BOLD);
            row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            row.setBackground(aquaButtonBg(c, false, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(c, 8);
            row.setOnClickListener(v -> { dlg.dismiss(); onPick.accept(idx); });
            list.addView(row, lp);
        }
        sv.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(c, 12);
        body.addView(sv, slp);

        TextView close = pillButton(c, c.getString(R.string.dlg_cancel), false, v -> dlg.dismiss());
        body.addView(close);
        dlg.show();
    }

    static void info(Context c, String title, List<String[]> rows) {
        LinearLayout outer = container(c, title, "EARWAX 4.0 AUDIO TELEMETRY");
        LinearLayout body = (LinearLayout) outer.getTag();
        final Dialog dlg = baseDialog(c, outer);

        for (String[] kv : rows) {
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
            TextView k = new TextView(c);
            k.setText(kv[0]);
            k.setTextColor(Theme.primary());
            k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            k.setTypeface(Theme.TF_BOLD);
            line.addView(k, new LinearLayout.LayoutParams(dp(c, 86), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView v = new TextView(c);
            v.setText(kv[1]);
            v.setTextColor(Theme.TEXT_MAIN);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            body.addView(line);
        }
        TextView ok = pillButton(c, c.getString(R.string.dlg_ok), true, v -> dlg.dismiss());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(c, 12);
        body.addView(ok, olp);
        dlg.show();
    }
}
