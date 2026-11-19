package com.example.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.example.ui.theme.SpaceCardBg
import com.example.ui.theme.SpaceDarkBg
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView

internal fun createAlbumNativeAdView(context: Context): NativeAdView {
    val nativeAdView = NativeAdView(context)
    val density = context.resources.displayMetrics.density

    val rootLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // Top media container (130dp height matching album cover)
    val mediaContainer = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (130 * density).toInt()
        )
        clipToOutline = true
        val cornerRadiusPx = 12 * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    val mediaView = MediaView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    val fallbackImageView = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    mediaContainer.addView(mediaView)
    mediaContainer.addView(fallbackImageView)

    val paddingPx = (12 * density).toInt()
    val detailsLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
    }

    val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val adBadge = TextView(context).apply {
        text = "Ad"
        textSize = 10f
        setTypeface(null, Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        background = GradientDrawable().apply {
            setColor(TealPrimary.toArgb())
            cornerRadius = 4 * density
        }
        val pxH = (5 * density).toInt()
        val pxV = (2 * density).toInt()
        setPadding(pxH, pxV, pxH, pxV)
    }

    val headlineView = TextView(context).apply {
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TextLight.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (6 * density).toInt()
        }
    }

    topRow.addView(adBadge)
    topRow.addView(headlineView)

    val bodyView = TextView(context).apply {
        textSize = 12f
        setTextColor(TextMuted.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (2 * density).toInt()
        }
    }

    val ctaView = TextView(context).apply {
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TealPrimary.toArgb())
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (4 * density).toInt()
        }
    }

    detailsLayout.addView(topRow)
    detailsLayout.addView(bodyView)
    detailsLayout.addView(ctaView)

    rootLayout.addView(mediaContainer)
    rootLayout.addView(detailsLayout)
    nativeAdView.addView(rootLayout)

    nativeAdView.mediaView = mediaView
    nativeAdView.headlineView = headlineView
    nativeAdView.bodyView = bodyView
    nativeAdView.callToActionView = ctaView
    nativeAdView.imageView = fallbackImageView

    return nativeAdView
}

internal fun bindAlbumNativeAdView(nativeAdView: NativeAdView, nativeAd: NativeAd) {
    val headlineText = nativeAd.headline
    (nativeAdView.headlineView as? TextView)?.apply {
        text = headlineText.orEmpty()
        visibility = if (headlineText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val bodyText = nativeAd.body ?: nativeAd.advertiser
    (nativeAdView.bodyView as? TextView)?.apply {
        text = bodyText.orEmpty()
        visibility = if (bodyText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val ctaText = nativeAd.callToAction
    (nativeAdView.callToActionView as? TextView)?.apply {
        text = ctaText.orEmpty()
        visibility = if (ctaText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val mediaView = nativeAdView.mediaView
    val fallbackImageView = nativeAdView.imageView as? ImageView
    val mediaContent = nativeAd.mediaContent
    val fallbackDrawable = nativeAd.images.firstOrNull()?.drawable ?: nativeAd.icon?.drawable

    if (mediaContent != null) {
        mediaView?.mediaContent = mediaContent
        mediaView?.visibility = View.VISIBLE
        fallbackImageView?.setImageDrawable(null)
        fallbackImageView?.visibility = View.GONE
    } else if (fallbackDrawable != null) {
        mediaView?.visibility = View.GONE
        fallbackImageView?.setImageDrawable(fallbackDrawable)
        fallbackImageView?.visibility = View.VISIBLE
    } else {
        mediaView?.visibility = View.GONE
        fallbackImageView?.setImageDrawable(null)
        fallbackImageView?.visibility = View.GONE
    }

    nativeAdView.setNativeAd(nativeAd)
}

internal fun createArtistNativeAdView(context: Context): NativeAdView {
    val nativeAdView = NativeAdView(context)
    val density = context.resources.displayMetrics.density
    val paddingPx = (16 * density).toInt()

    val rootLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val iconSizePx = (72 * density).toInt()
    val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
            bottomMargin = (8 * density).toInt()
        }
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        val cornerRadiusPx = 36 * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    val adBadge = TextView(context).apply {
        text = "Sponsored"
        textSize = 10f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TealPrimary.toArgb())
        background = GradientDrawable().apply {
            setColor(SpaceDarkBg.toArgb())
            cornerRadius = 4 * density
        }
        val pxH = (6 * density).toInt()
        val pxV = (2 * density).toInt()
        setPadding(pxH, pxV, pxH, pxV)
    }

    val headlineView = TextView(context).apply {
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TextLight.toArgb())
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (6 * density).toInt()
        }
    }

    val ctaView = TextView(context).apply {
        textSize = 12f
        setTextColor(TextMuted.toArgb())
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (2 * density).toInt()
        }
    }

    rootLayout.addView(iconView)
    rootLayout.addView(adBadge)
    rootLayout.addView(headlineView)
    rootLayout.addView(ctaView)
    nativeAdView.addView(rootLayout)

    nativeAdView.iconView = iconView
    nativeAdView.headlineView = headlineView
    nativeAdView.callToActionView = ctaView

    return nativeAdView
}

internal fun bindArtistNativeAdView(nativeAdView: NativeAdView, nativeAd: NativeAd) {
    val headlineText = nativeAd.headline
    (nativeAdView.headlineView as? TextView)?.apply {
        text = headlineText.orEmpty()
        visibility = if (headlineText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val ctaText = nativeAd.callToAction ?: nativeAd.advertiser
    (nativeAdView.callToActionView as? TextView)?.apply {
        text = ctaText.orEmpty()
        visibility = if (ctaText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val iconDrawable = nativeAd.icon?.drawable ?: nativeAd.images.firstOrNull()?.drawable
    val imageView = nativeAdView.iconView as? ImageView
    if (iconDrawable != null) {
        imageView?.setImageDrawable(iconDrawable)
        imageView?.visibility = View.VISIBLE
    } else {
        imageView?.setImageDrawable(null)
        imageView?.visibility = View.GONE
    }

    nativeAdView.setNativeAd(nativeAd)
}

internal fun createSongNativeAdView(context: Context): NativeAdView {
    val nativeAdView = NativeAdView(context)
    val density = context.resources.displayMetrics.density

    val rootLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()
        setPadding(padH, padV, padH, padV)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val iconSizePx = (44 * density).toInt()
    val iconView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
            marginEnd = (12 * density).toInt()
        }
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        val cornerRadiusPx = 8 * density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
    }

    val centerCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    val headlineView = TextView(context).apply {
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TextLight.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    val subRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (2 * density).toInt()
        }
    }

    val adBadge = TextView(context).apply {
        text = "Ad"
        textSize = 9f
        setTypeface(null, Typeface.BOLD)
        setTextColor(android.graphics.Color.WHITE)
        background = GradientDrawable().apply {
            setColor(TealPrimary.toArgb())
            cornerRadius = 3 * density
        }
        val pxH = (4 * density).toInt()
        val pxV = (1 * density).toInt()
        setPadding(pxH, pxV, pxH, pxV)
    }

    val bodyView = TextView(context).apply {
        textSize = 12f
        setTextColor(TextMuted.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginStart = (6 * density).toInt()
        }
    }

    subRow.addView(adBadge)
    subRow.addView(bodyView)

    centerCol.addView(headlineView)
    centerCol.addView(subRow)

    val ctaView = TextView(context).apply {
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TealPrimary.toArgb())
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (8 * density).toInt()
        }
        background = GradientDrawable().apply {
            setColor(SpaceCardBg.toArgb())
            cornerRadius = 12 * density
            setStroke((1 * density).toInt(), TealPrimary.copy(alpha = 0.5f).toArgb())
        }
        val pxH = (10 * density).toInt()
        val pxV = (4 * density).toInt()
        setPadding(pxH, pxV, pxH, pxV)
    }

    rootLayout.addView(iconView)
    rootLayout.addView(centerCol)
    rootLayout.addView(ctaView)

    nativeAdView.addView(rootLayout)

    nativeAdView.iconView = iconView
    nativeAdView.headlineView = headlineView
    nativeAdView.bodyView = bodyView
    nativeAdView.callToActionView = ctaView

    return nativeAdView
}

internal fun bindSongNativeAdView(nativeAdView: NativeAdView, nativeAd: NativeAd) {
    val headlineText = nativeAd.headline
    (nativeAdView.headlineView as? TextView)?.apply {
        text = headlineText.orEmpty()
        visibility = if (headlineText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val subText = nativeAd.advertiser ?: nativeAd.body
    (nativeAdView.bodyView as? TextView)?.apply {
        text = subText.orEmpty()
        visibility = if (subText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val ctaText = nativeAd.callToAction?.uppercase()
    (nativeAdView.callToActionView as? TextView)?.apply {
        text = ctaText.orEmpty()
        visibility = if (ctaText.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    val iconDrawable = nativeAd.icon?.drawable ?: nativeAd.images.firstOrNull()?.drawable
    val imageView = nativeAdView.iconView as? ImageView
    if (iconDrawable != null) {
        imageView?.setImageDrawable(iconDrawable)
        imageView?.visibility = View.VISIBLE
    } else {
        imageView?.setImageDrawable(null)
        imageView?.visibility = View.GONE
    }

    nativeAdView.setNativeAd(nativeAd)
}
