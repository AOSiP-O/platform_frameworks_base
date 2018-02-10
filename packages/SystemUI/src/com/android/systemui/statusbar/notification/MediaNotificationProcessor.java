/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.support.annotation.VisibleForTesting;
import android.support.v7.graphics.Palette;
import android.util.LayoutDirection;

import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;

import java.util.List;

/**
 * A class the processes media notifications and extracts the right text and background colors.
 */
public class MediaNotificationProcessor {

    /**
     * The fraction below which we select the vibrant instead of the light/dark vibrant color
     */
    private static final float POPULATION_FRACTION_FOR_MORE_VIBRANT = 1.0f;

    /**
     * Minimum saturation that a muted color must have if there exists if deciding between two
     * colors
     */
    private static final float MIN_SATURATION_WHEN_DECIDING = 0.19f;

    /**
     * Minimum fraction that any color must have to be picked up as a text color
     */
    private static final double MINIMUM_IMAGE_FRACTION = 0.002;

    /**
     * The population fraction to select the dominant color as the text color over a the colored
     * ones.
     */
    private static final float POPULATION_FRACTION_FOR_DOMINANT = 0.01f;

    /**
     * The population fraction to select a white or black color as the background over a color.
     */
    private static final float POPULATION_FRACTION_FOR_WHITE_OR_BLACK = 2.5f;
    private static final float BLACK_MAX_LIGHTNESS = 0.08f;
    private static final float WHITE_MIN_LIGHTNESS = 0.90f;
    private static final int RESIZE_BITMAP_AREA = 150 * 150;
    private final ImageGradientColorizer mColorizer;
    private final Context mContext;
    private float[] mFilteredBackgroundHsl = null;
    private Palette.Filter mBlackWhiteFilter = (rgb, hsl) -> !isWhiteOrBlack(hsl);

    /**
     * The context of the notification. This is the app context of the package posting the
     * notification.
     */
    private final Context mPackageContext;
    private boolean mIsLowPriority;

    public MediaNotificationProcessor(Context context, Context packageContext) {
        this(context, packageContext, new ImageGradientColorizer());
    }

    @VisibleForTesting
    MediaNotificationProcessor(Context context, Context packageContext,
            ImageGradientColorizer colorizer) {
        mContext = context;
        mPackageContext = packageContext;
        mColorizer = colorizer;
    }

    /**
     * Processes a builder of a media notification and calculates the appropriate colors that should
     * be used.
     *
     * @param notification the notification that is being processed
     * @param builder the recovered builder for the notification. this will be modified
     */
    public void processNotification(Notification notification, Notification.Builder builder) {
        Icon largeIcon = notification.getLargeIcon();
        Bitmap bitmap = null;
        Drawable drawable = null;
        if (largeIcon != null) {
            // We're transforming the builder, let's make sure all baked in RemoteViews are
            // rebuilt!
            builder.setRebuildStyledRemoteViews(true);
            drawable = largeIcon.loadDrawable(mPackageContext);
            int backgroundColor = 0;
            if (notification.isColorizedMedia()) {
                int width = drawable.getIntrinsicWidth();
                int height = drawable.getIntrinsicHeight();
                int area = width * height;
                if (area > RESIZE_BITMAP_AREA) {
                    double factor = Math.sqrt((float) RESIZE_BITMAP_AREA / area);
                    width = (int) (factor * width);
                    height = (int) (factor * height);
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);

                // for the background we only take the left side of the image to ensure
                // a smooth transition
                Palette.Builder paletteBuilder = Palette.from(bitmap)
                        .setRegion(0, 0, bitmap.getWidth() / 2, bitmap.getHeight())
                        .clearFilters() // we want all colors, red / white / black ones too!
                        .resizeBitmapArea(RESIZE_BITMAP_AREA);
                Palette palette = paletteBuilder.generate();
                backgroundColor = findBackgroundColorAndFilter(palette);
                // we want most of the full region again, slightly shifted to the right
                float textColorStartWidthFraction = 0.4f;
                paletteBuilder.setRegion((int) (bitmap.getWidth() * textColorStartWidthFraction), 0,
                        bitmap.getWidth(),
                        bitmap.getHeight());
                if (mFilteredBackgroundHsl != null) {
                    paletteBuilder.addFilter((rgb, hsl) -> {
                        // at least 10 degrees hue difference
                        float diff = Math.abs(hsl[0] - mFilteredBackgroundHsl[0]);
                        return diff > 10 && diff < 350;
                    });
                }
                paletteBuilder.addFilter(mBlackWhiteFilter);
                palette = paletteBuilder.generate();
                int foregroundColor = selectForegroundColor(backgroundColor, palette);
                builder.setColorPalette(backgroundColor, foregroundColor);
            } else {
                int id = mIsLowPriority
                        ? R.color.notification_material_background_low_priority_color
                        : R.color.notification_material_background_color;
                backgroundColor = mContext.getColor(id);
            }
            Bitmap colorized;
            // prevent the artwork to be recolored on reinflation
            if (!builder.getArtworkColorizedExtras()) {
                colorized = mColorizer.colorize(drawable, backgroundColor,
                        mContext.getResources().getConfiguration().getLayoutDirection() ==
                                LayoutDirection.RTL);
                builder.setArtworkColorizedExtras(true);
            } else {
                colorized = ((BitmapDrawable) drawable).getBitmap();
            }
            builder.setLargeIcon(Icon.createWithBitmap(colorized));
        }
    }

    private int selectForegroundColor(int backgroundColor, Palette palette) {
        if (NotificationColorUtil.isColorLight(backgroundColor)) {
            return selectForegroundColorForSwatches(palette.getDarkVibrantSwatch(),
                    palette.getVibrantSwatch(),
                    palette.getDarkMutedSwatch(),
                    palette.getMutedSwatch(),
                    palette.getDominantSwatch(),
                    Color.BLACK);
        } else {
            return selectForegroundColorForSwatches(palette.getLightVibrantSwatch(),
                    palette.getVibrantSwatch(),
                    palette.getLightMutedSwatch(),
                    palette.getMutedSwatch(),
                    palette.getDominantSwatch(),
                    Color.WHITE);
        }
    }

    private int selectForegroundColorForSwatches(Palette.Swatch moreVibrant,
            Palette.Swatch vibrant, Palette.Swatch moreMutedSwatch, Palette.Swatch mutedSwatch,
            Palette.Swatch dominantSwatch, int fallbackColor) {
        Palette.Swatch coloredCandidate = selectVibrantCandidate(moreVibrant, vibrant);
        if (coloredCandidate == null) {
            coloredCandidate = selectMutedCandidate(mutedSwatch, moreMutedSwatch);
        }
        if (coloredCandidate != null) {
            if (dominantSwatch == coloredCandidate) {
                return coloredCandidate.getRgb();
            } else if ((float) coloredCandidate.getPopulation() / dominantSwatch.getPopulation()
                    < POPULATION_FRACTION_FOR_DOMINANT
                    && dominantSwatch.getHsl()[1] > MIN_SATURATION_WHEN_DECIDING) {
                return dominantSwatch.getRgb();
            } else {
                return coloredCandidate.getRgb();
            }
        } else if (hasEnoughPopulation(dominantSwatch)) {
            return dominantSwatch.getRgb();
        } else {
            return fallbackColor;
        }
    }

    private Palette.Swatch selectMutedCandidate(Palette.Swatch first,
            Palette.Swatch second) {
        boolean firstValid = hasEnoughPopulation(first);
        boolean secondValid = hasEnoughPopulation(second);
        if (firstValid && secondValid) {
            float firstSaturation = first.getHsl()[1];
            float secondSaturation = second.getHsl()[1];
            float populationFraction = first.getPopulation() / (float) second.getPopulation();
            if (firstSaturation * populationFraction > secondSaturation) {
                return first;
            } else {
                return second;
            }
        } else if (firstValid) {
            return first;
        } else if (secondValid) {
            return second;
        }
        return null;
    }

    private Palette.Swatch selectVibrantCandidate(Palette.Swatch first, Palette.Swatch second) {
        boolean firstValid = hasEnoughPopulation(first);
        boolean secondValid = hasEnoughPopulation(second);
        if (firstValid && secondValid) {
            int firstPopulation = first.getPopulation();
            int secondPopulation = second.getPopulation();
            if (firstPopulation / (float) secondPopulation
                    < POPULATION_FRACTION_FOR_MORE_VIBRANT) {
                return second;
            } else {
                return first;
            }
        } else if (firstValid) {
            return first;
        } else if (secondValid) {
            return second;
        }
        return null;
    }

    private boolean hasEnoughPopulation(Palette.Swatch swatch) {
        // We want a fraction that is at least 1% of the image
        return swatch != null
                && (swatch.getPopulation() / (float) RESIZE_BITMAP_AREA > MINIMUM_IMAGE_FRACTION);
    }

    private int findBackgroundColorAndFilter(Palette palette) {
        // by default we use the dominant palette
        Palette.Swatch dominantSwatch = palette.getDominantSwatch();
        if (dominantSwatch == null) {
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return Color.WHITE;
        }

        if (!isWhiteOrBlack(dominantSwatch.getHsl())) {
            mFilteredBackgroundHsl = dominantSwatch.getHsl();
            return dominantSwatch.getRgb();
        }
        // Oh well, we selected black or white. Lets look at the second color!
        List<Palette.Swatch> swatches = palette.getSwatches();
        float highestNonWhitePopulation = -1;
        Palette.Swatch second = null;
        for (Palette.Swatch swatch: swatches) {
            if (swatch != dominantSwatch
                    && swatch.getPopulation() > highestNonWhitePopulation
                    && !isWhiteOrBlack(swatch.getHsl())) {
                second = swatch;
                highestNonWhitePopulation = swatch.getPopulation();
            }
        }
        if (second == null) {
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return dominantSwatch.getRgb();
        }
        if (dominantSwatch.getPopulation() / highestNonWhitePopulation
                > POPULATION_FRACTION_FOR_WHITE_OR_BLACK) {
            // The dominant swatch is very dominant, lets take it!
            // We're not filtering on white or black
            mFilteredBackgroundHsl = null;
            return dominantSwatch.getRgb();
        } else {
            mFilteredBackgroundHsl = second.getHsl();
            return second.getRgb();
        }
    }

    private boolean isWhiteOrBlack(float[] hsl) {
        return isBlack(hsl) || isWhite(hsl);
    }


    /**
     * @return true if the color represents a color which is close to black.
     */
    private boolean isBlack(float[] hslColor) {
        return hslColor[2] <= BLACK_MAX_LIGHTNESS;
    }

    /**
     * @return true if the color represents a color which is close to white.
     */
    private boolean isWhite(float[] hslColor) {
        return hslColor[2] >= WHITE_MIN_LIGHTNESS;
    }

    public void setIsLowPriority(boolean isLowPriority) {
        mIsLowPriority = isLowPriority;
    }
}
