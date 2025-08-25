package com.example.apptranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.apptranslate.databinding.ActivityMainBinding
import com.example.apptranslate.ui.ImageTranslateActivity

/**
 * MainActivity - Điểm vào chính của ứng dụng.
 * Chứa toolbar, bottom navigation, và là nơi host các Fragment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private var currentMenu: Menu? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Camera permission result: $isGranted")

        // Gửi broadcast kết quả về OverlayService
        val intent = Intent("com.example.apptranslate.CAMERA_PERMISSION_GRANTED").apply {
            putExtra("PERMISSION_GRANTED", isGranted)
        }
        sendBroadcast(intent)

        if (isGranted) {
            // Start ImageTranslateActivity trực tiếp nếu có quyền
            startImageTranslateActivity()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.translateFragment)
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNav.setupWithNavController(navController)
        setupDestinationChangedListener()

        // Xử lý intent khi app được mở từ service
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        Log.d(TAG, "Handling intent: ${intent?.action}")

        when (intent?.action) {
            "REQUEST_CAMERA_PERMISSION_FOR_IMAGE_TRANSLATE" -> {
                Log.d(TAG, "Processing camera permission request")
                requestCameraPermissionForImageTranslate()
            }
        }
    }

    private fun requestCameraPermissionForImageTranslate() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted")
                // Gửi broadcast ngay lập tức
                val intent = Intent("com.example.apptranslate.CAMERA_PERMISSION_GRANTED").apply {
                    putExtra("PERMISSION_GRANTED", true)
                }
                sendBroadcast(intent)

                // Start ImageTranslateActivity
                startImageTranslateActivity()
            }
            else -> {
                Log.d(TAG, "Requesting camera permission")
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startImageTranslateActivity() {
        try {
            Log.d(TAG, "Starting ImageTranslateActivity")
            val intent = Intent(this, ImageTranslateActivity::class.java).apply {
                action = ImageTranslateActivity.ACTION_TRANSLATE_IMAGE
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ImageTranslateActivity: ${e.message}")
        }
    }

    private fun setupDestinationChangedListener() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateMenuVisibility(destination.id)
        }
    }

    private fun updateMenuVisibility(destinationId: Int) {
        currentMenu?.let { menu ->
            val shouldShowMenu = destinationId == R.id.homeFragment
            for (i in 0 until menu.size()) {
                menu.getItem(i).isVisible = shouldShowMenu
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        currentMenu = menu
        updateMenuVisibility(navController.currentDestination?.id ?: R.id.homeFragment)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                navController.navigate(R.id.helpFragment)
                true
            }
            R.id.action_settings -> {
                navController.navigate(R.id.settingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}