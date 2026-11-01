package top.czyxh.pan;

import android.content.Context;
import android.graphics.Color;
import com.google.android.material.color.MaterialColors;

/**
 * Resolves Material You (Monet) dynamic colors extracted from the user's system wallpaper on Android 12+.
 * Falls back to Google's standard Material 3 color harmony palette on older Android versions.
 */
public class M3Colors {
    public final int surface;
    public final int surfaceContainer;
    public final int surfaceContainerLow;
    public final int surfaceContainerHigh;
    public final int surfaceContainerHighest;
    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int secondaryContainer;
    public final int onSecondaryContainer;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int outline;
    public final int outlineVariant;

    public M3Colors(Context context) {
        this.surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.parseColor("#111318"));
        this.surfaceContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.parseColor("#1E2025"));
        this.surfaceContainerLow = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.parseColor("#191C20"));
        this.surfaceContainerHigh = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#282A2F"));
        this.surfaceContainerHighest = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.parseColor("#33353A"));
        this.primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#A8C7FA"));
        this.onPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.parseColor("#062E6F"));
        this.primaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.parseColor("#0842A0"));
        this.onPrimaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.parseColor("#D3E3FD"));
        this.secondaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSecondaryContainer, Color.parseColor("#004D61"));
        this.onSecondaryContainer = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.parseColor("#C2E7FF"));
        this.onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#E2E2E9"));
        this.onSurfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#C4C6D0"));
        this.outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, Color.parseColor("#8E9099"));
        this.outlineVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#44474F"));
    }
}
