package com.elmiedo.app

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class PageAdapter(
    private val pageCount: Int,
    private val renderPage: (Int) -> Bitmap?
) : RecyclerView.Adapter<PageAdapter.PageViewHolder>() {

    // Cache rendered pages for smooth scrolling
    private val bitmapCache = LruCache<Int, Bitmap>(8)

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.pageImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Try cache first
        var bitmap = bitmapCache.get(position)
        if (bitmap == null) {
            bitmap = renderPage(position)
            if (bitmap != null) {
                bitmapCache.put(position, bitmap)
            }
        }

        if (bitmap != null) {
            holder.imageView.setImageBitmap(bitmap)
        }

        // Pre-cache adjacent pages
        val nextPage = position + 1
        val prevPage = position - 1
        if (nextPage < pageCount && bitmapCache.get(nextPage) == null) {
            renderPage(nextPage)?.let { bitmapCache.put(nextPage, it) }
        }
        if (prevPage >= 0 && bitmapCache.get(prevPage) == null) {
            renderPage(prevPage)?.let { bitmapCache.put(prevPage, it) }
        }
    }

    override fun getItemCount(): Int = pageCount
}
