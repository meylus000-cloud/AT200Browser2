package ua.at200.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;

/** Маленька кнопка-іконка, яка малює себе сама (без картинок і шрифтових гліфів). */
public class IconView extends View {
    public static final int BACK = 0, FORWARD = 1, RELOAD = 2, STOP = 3, HOME = 4, STAR = 5,
            MENU = 6, PLUS = 7, CLOSE = 8, LOCK = 9, UP = 10, DOWN = 11;

    private int type;
    private int color = 0xFF4A4F55;
    private boolean filled;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public IconView(Context c, int type) {
        super(c);
        this.type = type;
        setClickable(true);
        setFocusable(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(0x26000000);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, pressed);
        bg.addState(new int[]{android.R.attr.state_focused}, pressed);
        bg.addState(new int[]{}, new ColorDrawable(0));
        setBackgroundDrawable(bg);
    }

    public void setType(int t) {
        if (type != t) {
            type = t;
            invalidate();
        }
    }

    public void setFilled(boolean f) {
        if (filled != f) {
            filled = f;
            invalidate();
        }
    }

    public void setIconColor(int c) {
        if (color != c) {
            color = c;
            invalidate();
        }
    }

    public void setActive(boolean on) {
        setEnabled(on);
        setAlpha(on ? 1f : 0.35f);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float s = Math.min(w, h) * 0.62f;
        c.save();
        c.translate((w - s) / 2f, (h - s) / 2f);
        c.scale(s / 24f, s / 24f);

        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f);

        switch (type) {
            case BACK:
                c.drawLine(19, 12, 5, 12, paint);
                c.drawLine(11, 6, 5, 12, paint);
                c.drawLine(5, 12, 11, 18, paint);
                break;
            case FORWARD:
                c.drawLine(5, 12, 19, 12, paint);
                c.drawLine(13, 6, 19, 12, paint);
                c.drawLine(19, 12, 13, 18, paint);
                break;
            case RELOAD:
                rect.set(5, 5, 19, 19);
                c.drawArc(rect, 40, 280, false, paint);
                paint.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(19.6f, 10.2f);
                path.lineTo(19.66f, 5.57f);
                path.lineTo(15.06f, 9.43f);
                path.close();
                c.drawPath(path, paint);
                break;
            case STOP:
                c.drawLine(6, 6, 18, 18, paint);
                c.drawLine(18, 6, 6, 18, paint);
                break;
            case CLOSE:
                paint.setStrokeWidth(2f);
                c.drawLine(7, 7, 17, 17, paint);
                c.drawLine(17, 7, 7, 17, paint);
                break;
            case HOME:
                c.drawLine(3, 12, 12, 4, paint);
                c.drawLine(12, 4, 21, 12, paint);
                c.drawLine(6, 10, 6, 20, paint);
                c.drawLine(6, 20, 18, 20, paint);
                c.drawLine(18, 20, 18, 10, paint);
                break;
            case STAR:
                path.reset();
                for (int i = 0; i < 10; i++) {
                    double a = Math.toRadians(-90 + i * 36);
                    float r = (i % 2 == 0) ? 9.5f : 4.2f;
                    float x = (float) (12 + r * Math.cos(a));
                    float y = (float) (12.5 + r * Math.sin(a));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                if (filled) {
                    paint.setColor(0xFFF4B400);
                    paint.setStyle(Paint.Style.FILL_AND_STROKE);
                    paint.setStrokeWidth(1.2f);
                }
                c.drawPath(path, paint);
                break;
            case MENU:
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 5, 2.2f, paint);
                c.drawCircle(12, 12, 2.2f, paint);
                c.drawCircle(12, 19, 2.2f, paint);
                break;
            case PLUS:
                c.drawLine(12, 5, 12, 19, paint);
                c.drawLine(5, 12, 19, 12, paint);
                break;
            case LOCK:
                rect.set(6, 11, 18, 20.5f);
                c.drawRoundRect(rect, 2, 2, paint);
                rect.set(8.5f, 4, 15.5f, 13);
                c.drawArc(rect, 180, 180, false, paint);
                c.drawLine(8.5f, 8.5f, 8.5f, 11, paint);
                c.drawLine(15.5f, 8.5f, 15.5f, 11, paint);
                break;
            case UP:
                c.drawLine(6, 15, 12, 9, paint);
                c.drawLine(12, 9, 18, 15, paint);
                break;
            case DOWN:
                c.drawLine(6, 9, 12, 15, paint);
                c.drawLine(12, 15, 18, 9, paint);
                break;
            default:
                break;
        }
        c.restore();
    }
}
