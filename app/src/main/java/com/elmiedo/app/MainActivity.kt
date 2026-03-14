package com.elmiedo.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

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

        viewPager = findViewById(R.id.viewPager)
        pageIndicator = findViewById(R.id.pageIndicator)

        // Open the embedded PDF
        if (!openPdf()) {
            Toast.makeText(this, "Error loading book", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val pageCount = pdfRenderer?.pageCount ?: 0

        // Set up the ViewPager with page flip transformer
        val adapter = PageAdapter(pageCount) { pageIndex ->
            renderPage(pageIndex)
        }
        viewPager.adapter = adapter
        viewPager.setPageTransformer(BookFlipTransformer())

        // Reduce offscreen page limit for memory efficiency with large PDFs
        viewPager.offscreenPageLimit = 2

        // Page indicator
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.text = "${position + 1} / $pageCount"
                // Auto-hide indicator after a moment
                pageIndicator.alpha = 1f
                pageIndicator.animate()
                    .alpha(0f)
                    .setStartDelay(2000)
                    .setDuration(500)
                    .start()
            }
        })

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
            // Copy PDF from assets to cache for PdfRenderer
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

    fun renderPage(pageIndex: Int): Bitmap? {
        val renderer = pdfRenderer ?: return null
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null

        return try {
            val page = renderer.openPage(pageIndex)

            // Render at 2x for crisp display on most screens
            val scale = 2
            val width = page.width * scale
            val height = page.height * scale

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
