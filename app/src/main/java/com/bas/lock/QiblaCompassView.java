package com.bas.lock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * North is derived from the true-north azimuth. The gold arrow shows the
 * Kaaba's bearing relative to the top edge of the device, not relative to
 * the screen's absolute coordinates or a fixed triangle glyph.
 */
public final class QiblaCompassView extends View {
    private static final int GOLD = Color.rgb(228, 184, 95);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double qiblaBearing = Double.NaN;
    private double trueHeading = Double.NaN;

    public QiblaCompassView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setDirections(double qibla, double heading) {
        qiblaBearing = qibla;
        trueHeading = heading;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.46f;
        if (r <= 0f) return;

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(7, 28, 50));
        c.drawCircle(centerX, centerY, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(GOLD);
        c.drawCircle(centerX, centerY, r - dp(2), p);
        p.setStyle(Paint.Style.FILL);

        if (Double.isNaN(trueHeading)) {
            p.setColor(Color.rgb(182, 199, 217));
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(20));
            c.drawText("—", centerX, centerY + dp(6), p);
            return;
        }

        // Ticks and cardinal letters remain aligned to the real world as
        // the user rotates the device.
        for (int bearing = 0; bearing < 360; bearing += 30) {
            double angle = Math.toRadians(bearing - trueHeading);
            float sine = (float) Math.sin(angle);
            float cosine = (float) Math.cos(angle);
            float outside = r * 0.92f;
            float inside = r * ((bearing % 90 == 0) ? 0.81f : 0.86f);
            p.setColor(bearing == 0 ? GOLD : Color.rgb(113, 142, 164));
            p.setStrokeWidth(dp(bearing % 90 == 0 ? 2 : 1));
            c.drawLine(centerX + sine * inside, centerY - cosine * inside,
                    centerX + sine * outside, centerY - cosine * outside, p);
        }

        String[] letters = {"N", "E", "S", "W"};
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(12));
        for (int index = 0; index < 4; index++) {
            double angle = Math.toRadians(index * 90d - trueHeading);
            float x = centerX + (float) Math.sin(angle) * r * 0.66f;
            float y = centerY - (float) Math.cos(angle) * r * 0.66f;
            p.setColor(index == 0 ? GOLD : Color.rgb(220, 231, 241));
            c.drawText(letters[index], x, y + dp(4), p);
        }

        if (!Double.isNaN(qiblaBearing)) {
            float relative = (float) ((qiblaBearing - trueHeading + 360) % 360);
            c.save();
            c.rotate(relative, centerX, centerY);

            // Gold arrow tip is always the side that points to the Kaaba.
            Path arrow = new Path();
            arrow.moveTo(centerX, centerY - r * 0.58f);
            arrow.lineTo(centerX + r * 0.20f, centerY + r * 0.06f);
            arrow.lineTo(centerX + r * 0.085f, centerY - r * 0.035f);
            arrow.lineTo(centerX + r * 0.085f, centerY + r * 0.48f);
            arrow.lineTo(centerX - r * 0.085f, centerY + r * 0.48f);
            arrow.lineTo(centerX - r * 0.085f, centerY - r * 0.035f);
            arrow.lineTo(centerX - r * 0.20f, centerY + r * 0.06f);
            arrow.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD);
            c.drawPath(arrow, p);
            p.setColor(Color.rgb(255, 245, 211));
            c.drawCircle(centerX, centerY, r * 0.09f, p);
            c.restore();
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
