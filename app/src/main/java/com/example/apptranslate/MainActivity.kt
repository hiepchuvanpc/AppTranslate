package com.example.apptranslate

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.apptranslate.databinding.ActivityMainBinding
import android.content.Intent
import com.example.apptranslate.ui.LanguageSelectionBottomSheet
/**
 * MainActivity - Entry point of the application
 * Contains the toolbar, bottom navigation, and fragment container
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private var currentMenu: Menu? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)

        // Set up Navigation Controller
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Configure top-level destinations (no back button shown)
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.translateFragment)
        )

        // Connect the toolbar with navigation controller
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Connect bottom navigation with navigation controller
        binding.bottomNav.setupWithNavController(navController)

        // Thiết lập listener cho sự thay đổi destination
        setupDestinationChangedListener()
    }

    /**
     * Thiết lập listener để theo dõi sự thay đổi destination
     * và điều khiển hiển thị menu tương ứng
     */
    private fun setupDestinationChangedListener() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateMenuVisibility(destination.id)
        }
    }

    /**
     * Cập nhật hiển thị menu dựa trên destination hiện tại
     */
    private fun updateMenuVisibility(destinationId: Int) {
        currentMenu?.let { menu ->
            val shouldShowMenu = destinationId == R.id.homeFragment

            // Hiển thị/ẩn tất cả menu items
            for (i in 0 until menu.size()) {
                menu.getItem(i).isVisible = shouldShowMenu
            }
        }
    }

    /**
     * Inflate the toolbar menu
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        currentMenu = menu

        // Cập nhật hiển thị menu cho destination hiện tại
        updateMenuVisibility(navController.currentDestination?.id ?: R.id.homeFragment)

        return true
    }

    // Thêm hàm onNewIntent để nhận action khi Activity đã chạy
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Cập nhật intent mới cho activity
        handleIntent(intent)
    }

    // Thêm hàm onResume để xử lý action khi Activity được mở lại từ nền
    override fun onResume() {
        super.onResume()
        handleIntent(intent)
    }

    // Tạo một hàm riêng để xử lý, tránh lặp code
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_SHOW_LANGUAGE_SHEET") {
            showLanguageBottomSheet()
            // Reset action để không bị gọi lại khi xoay màn hình
            intent.action = null
        }
    }

    private fun showLanguageBottomSheet() {
        // Tìm NavHostFragment để có childFragmentManager
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        if (navHostFragment != null && navHostFragment.isAdded) {
            val bottomSheet = LanguageSelectionBottomSheet.newInstance()
            // Dùng childFragmentManager của NavHostFragment để đảm bảo đúng lifecycle
            bottomSheet.show(navHostFragment.childFragmentManager, LanguageSelectionBottomSheet.TAG)
        }
    }
    /**
     * Handle toolbar menu item clicks
     */
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

    /**
     * Support back navigation with the toolbar
     */
    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}