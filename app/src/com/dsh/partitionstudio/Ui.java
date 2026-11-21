package com.dsh.partitionstudio;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

final class Ui {
    static int BG;
    static int BG_SOFT;
    static int CARD;
    static int CARD_2;
    static int STROKE;
    static int STROKE_SOFT;
    static int TEXT;
    static int TEXT_DIM;
    static int TEXT_FAINT;
    static int ACCENT;
    static int ACCENT_DARK;
    static int ACCENT_SOFT;
    static int AMBER;
    static int AMBER_SOFT;
    static int BLUE;
    static int PURPLE;
    static int RED;
    static int SUCCESS;
    static boolean DARK;

    static final String PREFS = "partition_prefs";
    static final String KEY_THEME = "theme_mode";
    static final String KEY_MONET = "monet";
    static final String KEY_ACCENT = "accent";
    static final String KEY_FLOATING = "floating_nav";
    static final String KEY_GLASS = "glass";

    private Ui() {}

    static void init(Context c) {
        BG = c.getColor(R.color.bg);
        BG_SOFT = c.getColor(R.color.surface_alt);
        CARD = c.getColor(R.color.surface);
        CARD_2 = c.getColor(R.color.surface_alt);
        STROKE = c.getColor(R.color.stroke);
        STROKE_SOFT = c.getColor(R.color.stroke_soft);
        TEXT = c.getColor(R.color.text);
        TEXT_DIM = c.getColor(R.color.text_dim);
        TEXT_FAINT = c.getColor(R.color.text_faint);
        ACCENT = c.getColor(R.color.primary);
        ACCENT_DARK = c.getColor(R.color.on_primary);
        ACCENT_SOFT = c.getColor(R.color.primary_soft);
        AMBER = c.getColor(R.color.warning);
        AMBER_SOFT = c.getColor(R.color.warning_soft);
        BLUE = TEXT_DIM;
        PURPLE = TEXT_DIM;
        RED = c.getColor(R.color.danger);
        SUCCESS = c.getColor(R.color.success);
        DARK = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int accent = prefs.getInt(KEY_ACCENT, 0);
        boolean monet = prefs.getBoolean(KEY_MONET, false);
        int chosen = 0;
        if (monet && android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                chosen = c.getColor(DARK ? android.R.color.system_accent1_300
                        : android.R.color.system_accent1_200);
            } catch (Exception ignored) {}
        }
        if (chosen == 0 && accent != 0) chosen = accent;
        if (chosen != 0) {
            ACCENT = chosen;
            ACCENT_SOFT = blend(chosen, CARD, DARK ? 0.72f : 0.86f);
            ACCENT_DARK = luminance(chosen) > 150 ? 0xFF111311 : 0xFFFFFFFF;
        }
    }

    static int blend(int fg, int bg, float bgWeight) {
        float t = Math.max(0f, Math.min(1f, bgWeight));
        int r = (int) (Color.red(fg) * (1f - t) + Color.red(bg) * t);
        int g = (int) (Color.green(fg) * (1f - t) + Color.green(bg) * t);
        int b = (int) (Color.blue(fg) * (1f - t) + Color.blue(bg) * t);
        return Color.rgb(r, g, b);
    }

    static int luminance(int color) {
        return (int) (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color));
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    static Typeface mono() {
        return Typeface.create("monospace", Typeface.NORMAL);
    }

    static GradientDrawable solid(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable outline(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = solid(fill, radiusDp, c);
        d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    static GradientDrawable gradient(Context c, int[] colors, float radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static RippleDrawable ripple(Context c, Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    static TextView text(Context c, CharSequence value, float size, int color, boolean medium) {
        TextView tv = new TextView(c);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(size);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(0, 1.15f);
        if (medium) tv.setTypeface(medium());
        return tv;
    }

    static TextView label(Context c, String value, int color) {
        TextView tv = text(c, value, 10.5f, color, true);
        tv.setLetterSpacing(0.03f);
        return tv;
    }

    static TextView button(Context c, String value, int fill, int textColor, int stroke) {
        TextView tv = text(c, value, 14f, textColor, true);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = stroke == 0
                ? solid(fill, 16f, c)
                : outline(c, fill, 16f, stroke, 1f);
        tv.setBackground(ripple(c, bg, DARK ? 0x26FFFFFF : 0x16000000));
        tv.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    static ImageButton iconButton(Context c, int drawableRes, String desc, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(drawableRes);
        b.setImageTintList(ColorStateList.valueOf(TEXT));
        b.setScaleType(ImageButton.ScaleType.CENTER);
        b.setContentDescription(desc);
        b.setBackground(ripple(c, outline(c, CARD, 50f, STROKE, 1f),
                DARK ? 0x26FFFFFF : 0x14000000));
        b.setPadding(dp(c, 9), dp(c, 9), dp(c, 9), dp(c, 9));
        b.setOnClickListener(click);
        return b;
    }

    static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static void margins(View v, int l, int t, int r, int b, Context c) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        }
    }

    static void pad(View v, int l, int t, int r, int b, Context c) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    static TextView pill(Context c, String value, int textColor, int fill, int stroke) {
        TextView tv = text(c, value, 11f, textColor, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        tv.setLetterSpacing(0.04f);
        GradientDrawable bg = stroke == 0 ? solid(fill, 20f, c) : outline(c, fill, 20f, stroke, 1f);
        tv.setBackground(bg);
        return tv;
    }

    static Dialog dialog(Activity activity, View content, boolean cancelable) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout holder = new FrameLayout(activity);
        int side = dp(activity, 12);
        int bottom = dp(activity, 10);
        holder.setPadding(side, 0, side, bottom);
        holder.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(holder);
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.SheetAnimation);
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.dimAmount = DARK ? 0.62f : 0.32f;
            w.setAttributes(attrs);
        }
        d.setOnShowListener(di -> {
            Window win = d.getWindow();
            if (win != null) {
                int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels, dp(activity, 560));
                win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        return d;
    }

    static void toast(Context c, String value) {
        Toast.makeText(c, value, Toast.LENGTH_SHORT).show();
    }

    static void copy(Context c, String label, String value) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, value));
            toast(c, "已复制");
        }
    }

    static String size(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        if (i < 0) i = 0;
        if (v >= 100) return String.format(Locale.US, "%.0f %s", v, units[i]);
        if (v >= 10) return String.format(Locale.US, "%.1f %s", v, units[i]);
        return String.format(Locale.US, "%.2f %s", v, units[i]);
    }

    static String ellipsizeMiddle(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int side = (max - 1) / 2;
        return s.substring(0, side) + "…" + s.substring(s.length() - side);
    }

    static String hexDump(byte[] data, int maxBytes) {
        if (data == null) return "";
        int n = Math.min(data.length, maxBytes);
        if (n == 0) return "（空）";
        StringBuilder sb = new StringBuilder(n * 4);
        for (int off = 0; off < n; off += 16) {
            sb.append(String.format(Locale.US, "%08x  ", off));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (off + i < n) {
                    int b = data[off + i] & 0xFF;
                    sb.append(String.format(Locale.US, "%02x ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    sb.append("   ");
                    ascii.append(' ');
                }
                if (i == 7) sb.append(' ');
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString().trim();
    }

    static boolean allZero(byte[] d, int n) {
        for (int i = 0; i < Math.min(d.length, n); i++) if (d[i] != 0) return false;
        return true;
    }

    static boolean startsWith(byte[] d, int off, int... val) {
        if (d == null || d.length < off + val.length) return false;
        for (int i = 0; i < val.length; i++) {
            if ((d[off + i] & 0xFF) != val[i]) return false;
        }
        return true;
    }

    static boolean startsWithAscii(byte[] d, int off, String s) {
        if (d == null || d.length < off + s.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((d[off + i] & 0xFF) != (s.charAt(i) & 0xFF)) return false;
        }
        return true;
    }

    static String guessType(byte[] d) {
        if (d == null || d.length == 0) return "未知";
        if (allZero(d, Math.min(512, d.length))) return "全零 / 空白";
        if (startsWith(d, 0, 0x3A, 0xFF, 0x26, 0xED)) return "Android Sparse 稀疏镜像";
        if (startsWithAscii(d, 0, "ANDROID!")) return "Android Boot 镜像";
        if (startsWithAscii(d, 0, "AVB0")) return "AVB / vbmeta";
        if (startsWithAscii(d, 0, "EFI PART")) return "GPT 分区表";
        if (startsWith(d, 512, 0x45, 0x46, 0x49, 0x20, 0x50, 0x41, 0x52, 0x54)) return "GPT 分区表";
        if (startsWith(d, 0x438, 0x53, 0xEF)) return "ext4 文件系统";
        if (startsWithAscii(d, 82, "FAT32")) return "FAT32 文件系统";
        if (startsWithAscii(d, 54, "FAT16")) return "FAT16 文件系统";
        if (startsWith(d, 0, 0x1F, 0x8B)) return "GZIP 压缩数据";
        if (startsWith(d, 0, 0x50, 0x4B, 0x03, 0x04)) return "ZIP 压缩数据";
        if (startsWith(d, 0, 0x7F, 0x45, 0x4C, 0x46)) return "ELF 可执行数据";
        if (startsWith(d, 0, 0x04, 0x22, 0x4D, 0x18)) return "LZ4 压缩数据";
        if (startsWithAscii(d, 0, "LP")) return "Android 动态分区";
        return "原始数据 / 未知格式";
    }

    static boolean isCompressedOrSparse(byte[] d) {
        return startsWith(d, 0, 0x3A, 0xFF, 0x26, 0xED)
                || startsWith(d, 0, 0x1F, 0x8B)
                || startsWith(d, 0, 0x50, 0x4B, 0x03, 0x04)
                || startsWith(d, 0, 0x04, 0x22, 0x4D, 0x18);
    }

    static String oneLine(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > max) s = s.substring(0, max) + "…";
        return s;
    }

    static void keepScreenOn(Activity a, boolean on) {
        if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
