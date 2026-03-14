package com.elmiedo.app

import android.graphics.Bitmap
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

    // Cache rendered pages for smooth performance
    private val bitmapCache = LruCache<String, Bitmap>(10)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply FLAG_SECURE before setContentView to block screenshots/recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Full immersive mode - hide status bar and navigation
        setupImmersiveMode()

        pageCurlView = findViewById(R.id.pageCurlView)
        pageIndicator = findViewById(R.id.pageIndicator)

        // Open the embedded PDF
        if (!openPdf()) {
            Toast.makeText(this, "Error loading book", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val pageCount = pdfRenderer?.pageCount ?: 0

        // Set up the page curl view
        pageCurlView.pageProvider = object : PageCurlView.PageProvider {
            override fun getPageCount(): Int = pageCount

            override fun getPageBitmap(index: Int, width: Int, height: Int): Bitmap? {
                val key = "$index-$width-$height"
                bitmapCache.get(key)?.let { return it }

                val bitmap = renderPage(index, width, height) ?: return null
                bitmapCache.put(key, bitmap)
                return bitmap
            }
        }

        pageCurlView.onPageChanged = { page, total ->
            pageIndicator.text = "${page + 1} / $total"
            pageIndicator.alpha = 1f
            pageIndicator.animate()
                .alpha(0f)
                .setStartDelay(2000)
                .setDuration(500)
                .start()
        }

        // Show indicator initially
        pageIndicator.text = "1 / $pageCount"
        pageIndicator.animate()
            .alpha(0f)
            .setStartDelay(3000)
            .setDuration(500)
            .start()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep the screen on while reading
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

    private fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null

        return try {
            val page = renderer.openPage(pageIndex)

            // Scale to fit target size while maintaining aspect ratio
            val pageAspect = page.width.toFloat() / page.height.toFloat()
            val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()

            val bitmapWidth: Int
            val bitmapHeight: Int

            if (pageAspect > targetAspect) {
                bitmapWidth = targetWidth
                bitmapHeight = (targetWidth / pageAspect).toInt()
            } else {
                bitmapHeight = targetHeight
                bitmapWidth = (targetHeight * pageAspect).toInt()
            }

            // Create bitmap at target size for crisp rendering
            val bitmap = Bitmap.createBitmap(
                targetWidth.coerceAtLeast(1),
                targetHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(0xFFF5F5F0.toInt()) // Match background color

            // Center the page in the bitmap
            val left = (targetWidth - bitmapWidth) / 2
            val top = (targetHeight - bitmapHeight) / 2
            val destRect = android.graphics.Rect(left, top, left + bitmapWidth, top + bitmapHeight)

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
