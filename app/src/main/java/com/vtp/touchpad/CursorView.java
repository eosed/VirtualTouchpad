package com.vtp.touchpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** 屏幕光标：一个带描边的实心圆，纯视觉，不接收触摸。 */
class CursorView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    CursorView(Context c) {
        super(c);
        fill.setColor(Color.argb(235, 255, 255, 255));
        fill.setStyle(Paint.Style.FILL);
        ring.setColor(Color.argb(200, 0, 0, 0));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.5f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - 2f, fill);
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - 3f, ring);
    }
}
