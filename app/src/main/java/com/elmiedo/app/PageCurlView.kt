package com.elmiedo.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/**
 * Custom view that renders a realistic page curl effect.
 * Pages curl from right-to-left (forward) or left-to-right (backward)
 * with shadows, back-of-page rendering, and smooth touch tracking.
 */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface PageProvider {
        fun getPageCount(): Int
        fun getPageBitmap(index: Int, width: Int, height: Int): Bitmap?
    }

    var pageProvider: PageProvider? = null
        set(value) {
            field = value
            currentPageIndex = 0
            invalidate()
        }

    var onPageChanged: ((Int, Int) -> Unit)? = null

    private var currentPageIndex = 0

    // Curl state
    private var isCurling = false
    private var curlDirection = 0 // 1 = forward (right to left), -1 = backward (left to right)

    // Touch tracking
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var cornerX = 0f  // Current position of the dragged corner
    private var cornerY = 0f

    // Animation
    private var animator: ValueAnimator? = null
    private val animDuration = 400L

    // Page bitmaps (cached)
    private var currentBitmap: Bitmap? = null
    private var nextBitmap: Bitmap? = null

    // Drawing
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(0.7f, 0.7f, 0.7f, 1f) // Darken back of page
        })
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Threshold for starting a curl vs a tap
    private val touchSlop = 20f
    private var hasMoved = false

    // Minimum drag distance to commit a page turn (fraction of width)
    private val commitThreshold = 0.25f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val provider = pageProvider ?: return
        val pageCount = provider.getPageCount()
        if (pageCount == 0) return

        // Ensure current page bitmap
        if (currentBitmap == null || currentBitmap?.isRecycled == true) {
            currentBitmap = provider.getPageBitmap(currentPageIndex, width, height)
        }

        if (!isCurling) {
            // Just draw the current page
            currentBitmap?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
            }
            return
        }

        // Ensure next/prev page bitmap
        val targetIndex = if (curlDirection == 1) currentPageIndex + 1 else currentPageIndex - 1
        if (nextBitmap == null || nextBitmap?.isRecycled == true) {
            nextBitmap = provider.getPageBitmap(targetIndex, width, height)
        }

        if (curlDirection == 1) {
            drawForwardCurl(canvas, w, h)
        } else {
            drawBackwardCurl(canvas, w, h)
        }
    }

    private fun drawForwardCurl(canvas: Canvas, w: Float, h: Float) {
        // cornerX goes from w (start, right edge) to 0 or beyond (fully turned)
        // The fold line is perpendicular bisector of line from corner to its origin

        val originX = w
        val originY = h

        // Clamp corner position
        val cx = cornerX.coerceIn(-w * 0.5f, w)
        val cy = cornerY.coerceIn(0f, h * 1.5f)

        // Midpoint between corner and origin
        val midX = (cx + originX) / 2f
        val midY = (cy + originY) / 2f

        // Direction from origin to corner
        val dx = cx - originX
        val dy = cy - originY

        // The fold line passes through midpoint, perpendicular to (dx, dy)
        // Fold line direction: (-dy, dx)
        // We need to find where this line intersects the page edges

        val foldAngle = atan2(dy, dx)

        // Calculate the fold line intersections with page boundaries
        val intersections = calculateFoldIntersections(midX, midY, -dy, dx, w, h)

        if (intersections.size < 2) {
            // Fallback: just draw current page
            currentBitmap?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
            }
            return
        }

        val (p1, p2) = intersections

        // Build the path for the "uncurled" part (left side of fold)
        val uncurledPath = Path()
        buildUncurledPath(uncurledPath, p1, p2, w, h, curlDirection = 1)

        // Build the path for the "curled" part (right side of fold, reflected)
        val curledPath = Path()
        buildCurledPath(curledPath, p1, p2, w, h, curlDirection = 1)

        // 1. Draw next page as background (full page, will be partially covered)
        nextBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
        } ?: run {
            canvas.drawColor(Color.WHITE)
        }

        // 2. Draw shadow on next page along fold line
        drawFoldShadow(canvas, p1, p2, midX, midY, dx, dy, w, h, isForward = true)

        // 3. Draw current page clipped to uncurled region
        canvas.save()
        canvas.clipPath(uncurledPath)
        currentBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
        }
        canvas.restore()

        // 4. Draw back of current page (curled part, reflected across fold line)
        drawCurledBack(canvas, p1, p2, midX, midY, dx, dy, w, h, curledPath)
    }

    private fun drawBackwardCurl(canvas: Canvas, w: Float, h: Float) {
        // For backward curl, the page comes from the left
        val originX = 0f
        val originY = h

        val cx = cornerX.coerceIn(0f, w * 1.5f)
        val cy = cornerY.coerceIn(0f, h * 1.5f)

        val midX = (cx + originX) / 2f
        val midY = (cy + originY) / 2f

        val dx = cx - originX
        val dy = cy - originY

        val intersections = calculateFoldIntersections(midX, midY, -dy, dx, w, h)

        if (intersections.size < 2) {
            currentBitmap?.let {
                canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
            }
            return
        }

        val (p1, p2) = intersections

        val uncurledPath = Path()
        buildUncurledPath(uncurledPath, p1, p2, w, h, curlDirection = -1)

        val curledPath = Path()
        buildCurledPath(curledPath, p1, p2, w, h, curlDirection = -1)

        // 1. Draw next (previous) page as background
        nextBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
        } ?: run {
            canvas.drawColor(Color.WHITE)
        }

        // 2. Shadow
        drawFoldShadow(canvas, p1, p2, midX, midY, dx, dy, w, h, isForward = false)

        // 3. Current page clipped to uncurled region
        canvas.save()
        canvas.clipPath(uncurledPath)
        currentBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), pagePaint)
        }
        canvas.restore()

        // 4. Back of previous page (curled)
        drawCurledBack(canvas, p1, p2, midX, midY, dx, dy, w, h, curledPath)
    }

    private fun calculateFoldIntersections(
        px: Float, py: Float,  // Point on fold line
        dirX: Float, dirY: Float,  // Direction of fold line
        w: Float, h: Float
    ): List<PointF> {
        val points = mutableListOf<PointF>()

        if (abs(dirX) > 0.001f) {
            // Intersection with top edge (y=0)
            val t = (0f - py) / dirY * dirX / dirX  // Actually need parametric
            val tTop = if (abs(dirY) > 0.001f) -py / dirY else Float.MAX_VALUE
            val xTop = px + tTop * dirX
            if (xTop in -1f..w + 1f && abs(dirY) > 0.001f) {
                points.add(PointF(xTop.coerceIn(0f, w), 0f))
            }

            // Intersection with bottom edge (y=h)
            val tBottom = if (abs(dirY) > 0.001f) (h - py) / dirY else Float.MAX_VALUE
            val xBottom = px + tBottom * dirX
            if (xBottom in -1f..w + 1f && abs(dirY) > 0.001f) {
                points.add(PointF(xBottom.coerceIn(0f, w), h))
            }
        }

        if (abs(dirY) > 0.001f || points.size < 2) {
            // Intersection with left edge (x=0)
            val tLeft = if (abs(dirX) > 0.001f) -px / dirX else Float.MAX_VALUE
            val yLeft = py + tLeft * dirY
            if (yLeft in -1f..h + 1f && abs(dirX) > 0.001f) {
                points.add(PointF(0f, yLeft.coerceIn(0f, h)))
            }

            // Intersection with right edge (x=w)
            val tRight = if (abs(dirX) > 0.001f) (w - px) / dirX else Float.MAX_VALUE
            val yRight = py + tRight * dirY
            if (yRight in -1f..h + 1f && abs(dirX) > 0.001f) {
                points.add(PointF(w, yRight.coerceIn(0f, h)))
            }
        }

        // Remove duplicates and keep only 2
        val unique = points.distinctBy { "${it.x.toInt()},${it.y.toInt()}" }
        return if (unique.size >= 2) unique.take(2) else unique
    }

    private fun buildUncurledPath(path: Path, p1: PointF, p2: PointF, w: Float, h: Float, curlDirection: Int) {
        // The uncurled region is the part of the page that's still flat
        // For forward curl: left side of fold line
        // For backward curl: right side of fold line
        path.reset()

        if (curlDirection == 1) {
            // Forward: uncurled is the left portion
            path.moveTo(0f, 0f)
            path.lineTo(0f, h)

            // Add fold line points in the right order
            val bottomPoint = if (p1.y > p2.y) p1 else p2
            val topPoint = if (p1.y > p2.y) p2 else p1

            // Walk along the left and bottom edges, then the fold line
            if (bottomPoint.y >= h - 1) {
                path.lineTo(bottomPoint.x, h)
            } else {
                path.lineTo(0f, h)
                path.lineTo(bottomPoint.x, bottomPoint.y)
            }

            path.lineTo(topPoint.x, topPoint.y)

            if (topPoint.y <= 1) {
                path.lineTo(0f, 0f)
            } else {
                path.lineTo(topPoint.x, topPoint.y)
                path.lineTo(0f, 0f)
            }
            path.close()
        } else {
            // Backward: uncurled is the right portion
            path.moveTo(w, 0f)
            path.lineTo(w, h)

            val bottomPoint = if (p1.y > p2.y) p1 else p2
            val topPoint = if (p1.y > p2.y) p2 else p1

            if (bottomPoint.y >= h - 1) {
                path.lineTo(bottomPoint.x, h)
            } else {
                path.lineTo(w, h)
                path.lineTo(bottomPoint.x, bottomPoint.y)
            }

            path.lineTo(topPoint.x, topPoint.y)

            if (topPoint.y <= 1) {
                path.lineTo(w, 0f)
            }
            path.close()
        }
    }

    private fun buildCurledPath(path: Path, p1: PointF, p2: PointF, w: Float, h: Float, curlDirection: Int) {
        path.reset()
        // The curled region is the reflected part
        // This will be clipped after reflection transform

        if (curlDirection == 1) {
            // Forward: curled is the right portion (past fold line toward right edge)
            val bottomPoint = if (p1.y > p2.y) p1 else p2
            val topPoint = if (p1.y > p2.y) p2 else p1

            path.moveTo(topPoint.x, topPoint.y)
            path.lineTo(bottomPoint.x, bottomPoint.y)

            if (bottomPoint.y >= h - 1 && bottomPoint.x < w) {
                path.lineTo(w, h)
            }
            path.lineTo(w, h)
            path.lineTo(w, 0f)
            if (topPoint.y <= 1 && topPoint.x < w) {
                path.lineTo(topPoint.x, 0f)
            }
            path.close()
        } else {
            val bottomPoint = if (p1.y > p2.y) p1 else p2
            val topPoint = if (p1.y > p2.y) p2 else p1

            path.moveTo(topPoint.x, topPoint.y)
            path.lineTo(bottomPoint.x, bottomPoint.y)

            if (bottomPoint.y >= h - 1 && bottomPoint.x > 0) {
                path.lineTo(0f, h)
            }
            path.lineTo(0f, h)
            path.lineTo(0f, 0f)
            if (topPoint.y <= 1 && topPoint.x > 0) {
                path.lineTo(topPoint.x, 0f)
            }
            path.close()
        }
    }

    private fun drawFoldShadow(
        canvas: Canvas,
        p1: PointF, p2: PointF,
        midX: Float, midY: Float,
        dx: Float, dy: Float,
        w: Float, h: Float,
        isForward: Boolean
    ) {
        // Draw a gradient shadow along the fold line on the next page
        val shadowWidth = 30f
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return

        // Normal to fold line pointing toward the uncurled side
        val nx = if (isForward) -dx / len else dx / len
        val ny = if (isForward) -dy / len else dy / len

        val shader = LinearGradient(
            midX, midY,
            midX + nx * shadowWidth, midY + ny * shadowWidth,
            intArrayOf(0x44000000, 0x00000000),
            null,
            Shader.TileMode.CLAMP
        )
        shadowPaint.shader = shader

        canvas.save()
        canvas.drawRect(0f, 0f, w, h, shadowPaint)
        canvas.restore()
    }

    private fun drawCurledBack(
        canvas: Canvas,
        p1: PointF, p2: PointF,
        midX: Float, midY: Float,
        dx: Float, dy: Float,
        w: Float, h: Float,
        curledPath: Path
    ) {
        // Reflect the page content across the fold line
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return

        // Reflection matrix across the fold line
        // The fold line passes through (midX, midY) with direction (-dy, dx)
        val fdx = -dy / len  // Fold line direction (normalized)
        val fdy = dx / len

        // Reflection matrix: R = 2 * (n ⊗ n) - I where n is fold direction
        // Translate to origin, reflect, translate back
        val reflectMatrix = Matrix()
        reflectMatrix.setValues(floatArrayOf(
            2 * fdx * fdx - 1, 2 * fdx * fdy, 0f,
            2 * fdx * fdy, 2 * fdy * fdy - 1, 0f,
            0f, 0f, 1f
        ))

        // Translate so fold line passes through origin
        val fullMatrix = Matrix()
        fullMatrix.preTranslate(midX, midY)
        fullMatrix.preConcat(reflectMatrix)
        fullMatrix.preTranslate(-midX, -midY)

        canvas.save()

        // Clip to the uncurled side (where the back of page shows)
        // The back of the page appears on the opposite side of where the original curled region was
        val clipPath = Path()
        clipPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Apply reflection and draw
        canvas.concat(fullMatrix)

        // Clip to the original curled region (before reflection)
        canvas.clipPath(curledPath)

        // Draw the page content with darkened tint (back of page)
        currentBitmap?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, w, h), backPaint)
        }

        canvas.restore()

        // Draw edge shadow on the curled part
        drawEdgeShadow(canvas, p1, p2, midX, midY, dx, dy, w, h)
    }

    private fun drawEdgeShadow(
        canvas: Canvas,
        p1: PointF, p2: PointF,
        midX: Float, midY: Float,
        dx: Float, dy: Float,
        w: Float, h: Float
    ) {
        val shadowWidth = 15f
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return

        val nx = dx / len
        val ny = dy / len

        val shader = LinearGradient(
            midX - nx * shadowWidth, midY - ny * shadowWidth,
            midX, midY,
            intArrayOf(0x00000000, 0x33000000),
            null,
            Shader.TileMode.CLAMP
        )
        edgeShadowPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, edgeShadowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val provider = pageProvider ?: return false
        val pageCount = provider.getPageCount()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                touchStartX = event.x
                touchStartY = event.y
                hasMoved = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - touchStartX
                val deltaY = event.y - touchStartY

                if (!hasMoved && abs(deltaX) > touchSlop) {
                    hasMoved = true

                    // Determine direction
                    if (deltaX < 0 && currentPageIndex < pageCount - 1) {
                        // Swiping left = forward
                        curlDirection = 1
                        isCurling = true
                        nextBitmap = provider.getPageBitmap(currentPageIndex + 1, width, height)
                    } else if (deltaX > 0 && currentPageIndex > 0) {
                        // Swiping right = backward
                        curlDirection = -1
                        isCurling = true
                        nextBitmap = provider.getPageBitmap(currentPageIndex - 1, width, height)
                    }
                }

                if (isCurling) {
                    cornerX = event.x
                    cornerY = event.y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!hasMoved) {
                    // Tap: check which half was tapped
                    if (event.x > width / 2f && currentPageIndex < pageCount - 1) {
                        // Tap right half = next page (animate forward curl)
                        curlDirection = 1
                        isCurling = true
                        nextBitmap = provider.getPageBitmap(currentPageIndex + 1, width, height)
                        cornerX = width.toFloat()
                        cornerY = height.toFloat()
                        animateCurl(commit = true)
                    } else if (event.x <= width / 2f && currentPageIndex > 0) {
                        // Tap left half = previous page
                        curlDirection = -1
                        isCurling = true
                        nextBitmap = provider.getPageBitmap(currentPageIndex - 1, width, height)
                        cornerX = 0f
                        cornerY = height.toFloat()
                        animateCurl(commit = true)
                    }
                    return true
                }

                if (isCurling) {
                    // Decide whether to commit or cancel based on how far the page was dragged
                    val progress = if (curlDirection == 1) {
                        1f - (cornerX / width.toFloat())
                    } else {
                        cornerX / width.toFloat()
                    }
                    animateCurl(commit = progress > commitThreshold)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateCurl(commit: Boolean) {
        animator?.cancel()

        val startX = cornerX
        val startY = cornerY
        val w = width.toFloat()
        val h = height.toFloat()

        val targetX: Float
        val targetY: Float

        if (commit) {
            // Animate to fully turned
            targetX = if (curlDirection == 1) -w * 0.2f else w * 1.2f
            targetY = h
        } else {
            // Animate back to original position
            targetX = if (curlDirection == 1) w else 0f
            targetY = h
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            interpolator = DecelerateInterpolator(1.5f)

            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                cornerX = startX + (targetX - startX) * fraction
                cornerY = startY + (targetY - startY) * fraction
                invalidate()
            }

            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (commit) {
                        // Update page index
                        val provider = pageProvider ?: return
                        if (curlDirection == 1 && currentPageIndex < provider.getPageCount() - 1) {
                            currentPageIndex++
                        } else if (curlDirection == -1 && currentPageIndex > 0) {
                            currentPageIndex--
                        }
                        currentBitmap = nextBitmap
                        nextBitmap = null
                        onPageChanged?.invoke(currentPageIndex, provider.getPageCount())
                    } else {
                        nextBitmap = null
                    }
                    isCurling = false
                    invalidate()
                }
            })
            start()
        }
    }

    fun getCurrentPage(): Int = currentPageIndex

    fun goToPage(index: Int) {
        val provider = pageProvider ?: return
        if (index in 0 until provider.getPageCount()) {
            currentPageIndex = index
            currentBitmap = provider.getPageBitmap(index, width, height)
            nextBitmap = null
            isCurling = false
            invalidate()
            onPageChanged?.invoke(currentPageIndex, provider.getPageCount())
        }
    }
}
