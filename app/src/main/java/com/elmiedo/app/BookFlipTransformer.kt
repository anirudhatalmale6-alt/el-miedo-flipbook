package com.elmiedo.app

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Page transformer that creates a realistic book page-flip effect.
 * Pages rotate around their edge like turning a physical book page.
 */
class BookFlipTransformer : ViewPager2.PageTransformer {

    companion object {
        private const val MAX_ROTATION = 90f
        private const val MIN_SCALE = 0.75f
        private const val MIN_ALPHA = 0.5f
    }

    override fun transformPage(page: View, position: Float) {
        val absPosition = abs(position)

        when {
            position < -1f -> {
                // Page is off-screen to the left
                page.alpha = 0f
            }
            position <= 0f -> {
                // Current page (moving out to the left)
                page.alpha = 1f - absPosition * (1f - MIN_ALPHA)
                page.pivotX = page.width.toFloat()
                page.pivotY = page.height / 2f
                page.rotationY = MAX_ROTATION * position
                page.scaleX = 1f - absPosition * (1f - MIN_SCALE) * 0.3f
                page.scaleY = 1f - absPosition * (1f - MIN_SCALE) * 0.3f
                // Slight elevation for depth
                page.translationZ = -absPosition
            }
            position <= 1f -> {
                // Next page (coming in from the right)
                page.alpha = MIN_ALPHA + (1f - absPosition) * (1f - MIN_ALPHA)
                page.pivotX = 0f
                page.pivotY = page.height / 2f
                page.rotationY = MAX_ROTATION * position
                page.scaleX = MIN_SCALE + (1f - absPosition) * (1f - MIN_SCALE) * 0.3f
                page.scaleY = MIN_SCALE + (1f - absPosition) * (1f - MIN_SCALE) * 0.3f
                page.translationZ = -absPosition
            }
            else -> {
                // Page is off-screen to the right
                page.alpha = 0f
            }
        }
    }
}
