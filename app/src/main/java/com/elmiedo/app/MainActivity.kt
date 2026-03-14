package com.elmiedo.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var pageCurlView: PageCurlView
    private lateinit var pageIndicator: TextView
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    // Cache rendered spreads for smooth performance
    private val bitmapCache = LruCache<String, Bitmap>(8)

    // Book spread mapping: each spread contains either 1 or 2 PDF pages
    // Spread 0: page 0 alone (cover)
    // Spread 1: pages 1+2
    // Spread 2: pages 3+4
    // ...
    // Last spread: last page alone (if needed)
    private val spreads = mutableListOf<IntArray>()

    private val bgColor = 0xFFF5F5F0.toInt()
    private val dividerPaint = Paint().apply {
        color = 0x20000000 // Very subtle divider
        strokeWidth = 2f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply FLAG_SECURE before setContentView to block screenshots/recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupImmersiveMode()

        pageCurlView = findViewById(R.id.pageCurlView)
        pageIndicator = findViewById(R.id.pageIndicator)

        if (!openPdf()) {
            Toast.makeText(this, "Error loading book", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val totalPages = pdfRenderer?.pageCount ?: 0
        buildSpreads(totalPages)

        val spreadCount = spreads.size

        pageCurlView.pageProvider = object : PageCurlView.PageProvider {
            override fun getPageCount(): Int = spreadCount

            override fun getPageBitmap(index: Int, width: Int, height: Int): Bitmap? {
                if (index < 0 || index >= spreadCount) return null
                val key = "spread-$index-$width-$height"
                bitmapCache.get(key)?.let { return it }

                val bitmap = renderSpread(index, width, height) ?: return null
                bitmapCache.put(key, bitmap)
                return bitmap
            }
        }

        pageCurlView.onPageChanged = { spreadIndex, total ->
            val spread = spreads.getOrNull(spreadIndex)
            val label = if (spread != null && spread.size == 2) {
                "${spread[0] + 1}-${spread[1] + 1} / $totalPages"
            } else if (spread != null) {
                "${spread[0] + 1} / $totalPages"
            } else {
                "${spreadIndex + 1} / $total"
            }
            pageIndicator.text = label
            pageIndicator.alpha = 1f
            pageIndicator.animate()
                .alpha(0f)
                .setStartDelay(2000)
                .setDuration(500)
                .start()
        }

        // Show indicator initially
        pageIndicator.text = "1 / $totalPages"
        pageIndicator.animate()
            .alpha(0f)
            .setStartDelay(3000)
            .setDuration(500)
            .start()
    }

    private fun buildSpreads(totalPages: Int) {
        spreads.clear()
        if (totalPages == 0) return

        // First page (cover) alone
        spreads.add(intArrayOf(0))

        // Pair remaining pages: (1,2), (3,4), (5,6), ...
        var i = 1
        while (i < totalPages) {
            if (i + 1 < totalPages) {
                spreads.add(intArrayOf(i, i + 1))
                i += 2
            } else {
                // Last page alone
                spreads.add(intArrayOf(i))
                i++
            }
        }
    }

    private fun renderSpread(spreadIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val spread = spreads.getOrNull(spreadIndex) ?: return null

        return if (spread.size == 1) {
            renderSinglePage(spread[0], targetWidth, targetHeight)
        } else {
            renderDoublePage(spread[0], spread[1], targetWidth, targetHeight)
        }
    }

    private fun renderSinglePage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null

        return try {
            val page = renderer.openPage(pageIndex)
            val pageAspect = page.width.toFloat() / page.height.toFloat()

            // For single page in landscape, fit to height and center horizontally
            val fitHeight = targetHeight
            val fitWidth = (fitHeight * pageAspect).toInt().coerceAtMost(targetWidth)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(bgColor)

            val left = (targetWidth - fitWidth) / 2
            val top = (targetHeight - fitHeight) / 2
            val destRect = android.graphics.Rect(left, top, left + fitWidth, top + fitHeight)

            val matrix = android.graphics.Matrix()
            matrix.setRectToRect(
                android.graphics.RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()),
                android.graphics.RectF(destRect),
                android.graphics.Matrix.ScaleToFit.CENTER
            )

            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun renderDoublePage(leftPageIndex: Int, rightPageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (leftPageIndex < 0 || rightPageIndex >= renderer.pageCount) return null

        return try {
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(bgColor)

            val halfWidth = targetWidth / 2
            val margin = 4 // Small gap between pages

            // Render left page
            val leftPage = renderer.openPage(leftPageIndex)
            val leftAspect = leftPage.width.toFloat() / leftPage.height.toFloat()
            val leftAvailWidth = halfWidth - margin
            val leftFitHeight = targetHeight
            val leftFitWidth = (leftFitHeight * leftAspect).toInt().coerceAtMost(leftAvailWidth)
            val leftX = halfWidth - margin - leftFitWidth  // Right-align in left half
            val leftY = (targetHeight - leftFitHeight) / 2
            val leftRect = android.graphics.Rect(leftX, leftY, leftX + leftFitWidth, leftY + leftFitHeight)

            val leftMatrix = android.graphics.Matrix()
            leftMatrix.setRectToRect(
                android.graphics.RectF(0f, 0f, leftPage.width.toFloat(), leftPage.height.toFloat()),
                android.graphics.RectF(leftRect),
                android.graphics.Matrix.ScaleToFit.CENTER
            )
            leftPage.render(bitmap, null, leftMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            leftPage.close()

            // Render right page
            val rightPage = renderer.openPage(rightPageIndex)
            val rightAspect = rightPage.width.toFloat() / rightPage.height.toFloat()
            val rightAvailWidth = halfWidth - margin
            val rightFitHeight = targetHeight
            val rightFitWidth = (rightFitHeight * rightAspect).toInt().coerceAtMost(rightAvailWidth)
            val rightX = halfWidth + margin  // Left-align in right half
            val rightY = (targetHeight - rightFitHeight) / 2
            val rightRect = android.graphics.Rect(rightX, rightY, rightX + rightFitWidth, rightY + rightFitHeight)

            val rightMatrix = android.graphics.Matrix()
            rightMatrix.setRectToRect(
                android.graphics.RectF(0f, 0f, rightPage.width.toFloat(), rightPage.height.toFloat()),
                android.graphics.RectF(rightRect),
                android.graphics.Matrix.ScaleToFit.CENTER
            )
            rightPage.render(bitmap, null, rightMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            rightPage.close()

            // Draw subtle center divider line
            val canvas = Canvas(bitmap)
            canvas.drawLine(
                halfWidth.toFloat(), 0f,
                halfWidth.toFloat(), targetHeight.toFloat(),
                dividerPaint
            )

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveMode()
        }
    }

    private fun openPdf(): Boolean {
        return try {
            val file = File(cacheDir, "book.pdf")
            if (!file.exists()) {
                assets.open("book.pdf").use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            pdfRenderer?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
