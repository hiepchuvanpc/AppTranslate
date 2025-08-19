package com.example.apptranslate

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Class to manage overlay UI for translation feature
 * This will display on top of other apps when active
 */
class OverlayViewManager(private val context: Context) {
    
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false
    
    // Parameters for the overlay window
    private val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else 
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 100
    }
    
    /**
     * Create and show the overlay view
     */
    fun show() {
        if (isShowing) return
        
        // Create the overlay view
        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_translation, null)
        
        // Set up any UI components
        setupOverlayUI()
        
        // Add the view to window manager
        try {
            windowManager.addView(overlayView, params)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to show overlay: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Remove the overlay view
     */
    fun hide() {
        if (!isShowing || overlayView == null) return
        
        try {
            windowManager.removeView(overlayView)
            overlayView = null
            isShowing = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Update the translated text shown in the overlay
     */
    fun updateTranslatedText(text: String) {
        overlayView?.findViewById<TextView>(R.id.translatedTextView)?.text = text
    }
    
    /**
     * Set up the UI components and their listeners
     */
    private fun setupOverlayUI() {
        overlayView?.let { view ->
            // Close button to hide overlay
            view.findViewById<Button>(R.id.closeButton)?.setOnClickListener {
                hide()
            }
            
            // Example: Set initial text
            view.findViewById<TextView>(R.id.translatedTextView)?.text = "Translation will appear here"
        }
    }
    
    /**
     * Check if overlay is currently showing
     */
    fun isOverlayShowing(): Boolean = isShowing
}
