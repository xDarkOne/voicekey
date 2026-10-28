package dev.xdark.voicekey;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** VoiceKey's look, shared by the voice panel and the settings: dark rounded cards, a light pill for focus, an amber accent. */
final class Look {
    static final int CARD = 0xF2161719;
    static final int CARD_LINE = 0x1FFFFFFF;
    static final int TEXT = 0xFFF1F3F4;
    static final int MUTED = 0xFF9AA0A6;
    static final int ACCENT = 0xFFF4B63F;
    static final int FOCUS = 0xFFE8EAED;
    static final int ON_FOCUS = 0xFF15161A;
    static final int SCREEN = 0xFF101114;

    private Look() {}

    static float dp(Context c) {
        return c.getResources().getDisplayMetrics().density;
    }

    static GradientDrawable card(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(28 * dp(c));
        d.setStroke((int) Math.max(1, dp(c)), CARD_LINE);
        return d;
    }

    static GradientDrawable pill(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(18 * dp(c));
        return d;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }
}
