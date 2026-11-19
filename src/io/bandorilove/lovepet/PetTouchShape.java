package io.bandorilove.lovepet;

import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;

final class PetTouchShape {
    private static final int COLUMNS = 40;
    private static final int ROWS = 58;
    private String mask = "";
    private RectF caption, menu, menuButton;
    private PointF grip;

    void setModel(String shape, boolean resetGrip) {
        if (shape.length() != COLUMNS * ROWS) return;
        mask = shape;
        if (!resetGrip && grip != null) return;
        float nearest = Float.POSITIVE_INFINITY;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                if (mask.charAt(row * COLUMNS + col) != '1') continue;
                float x = (col + .5f) / COLUMNS, y = (row + .5f) / ROWS;
                float score = (x - .5f) * (x - .5f) + (y - .54f) * (y - .54f);
                if (score < nearest) {
                    nearest = score;
                    grip = new PointF(x, y);
                }
            }
        }
    }

    PointF grip() { return grip; }

    void setControl(String name, RectF bounds) {
        switch (name) {
            case "caption": caption = bounds; break;
            case "menu": menu = bounds; break;
            case "menuButton": menuButton = bounds; break;
        }
    }

    boolean hitsControls(float x, float y) {
        return (caption != null && caption.contains(x, y))
                || (menu != null && menu.contains(x, y))
                || (menuButton != null && menuButton.contains(x, y));
    }

    Region modelRegion(int width, int height, int padding) {
        Region result = new Region();
        if (!mask.isEmpty()) {
            for (int row = 0; row < ROWS; row++) {
                int top = row * height / ROWS;
                int bottom = (row + 1) * height / ROWS;
                int offset = row * COLUMNS;
                for (int col = 0; col < COLUMNS; col++) {
                    if (mask.charAt(offset + col) != '1') continue;
                    int start = col;
                    while (col + 1 < COLUMNS && mask.charAt(offset + col + 1) == '1') col++;
                    result.union(new Rect(start * width / COLUMNS - padding, top - padding,
                            (col + 1) * width / COLUMNS + padding, bottom + padding));
                }
            }
        }
        result.op(0, 0, width, height, Region.Op.INTERSECT);
        return result;
    }

    void addControls(Region result, int width, int height) {
        for (RectF bounds : new RectF[] {caption, menu, menuButton}) {
            if (bounds == null) continue;
            result.union(new Rect((int) Math.floor(bounds.left * width),
                    (int) Math.floor(bounds.top * height), (int) Math.ceil(bounds.right * width),
                    (int) Math.ceil(bounds.bottom * height)));
        }
    }
}
