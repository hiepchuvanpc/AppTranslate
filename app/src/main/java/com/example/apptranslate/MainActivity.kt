// File: app/src/main/java/com/example/apptranslate/MainActivity.kt

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

/**
 * MainActivity - Điểm vào chính của ứng dụng.
 * Chứa toolbar, bottom navigation, và là nơi host các Fragment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private var currentMenu: Menu? = null

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
    }

    // Lưu ý: Các hàm onNewIntent, handleIntent, showLanguageBottomSheet đã được xóa
    // vì chúng không còn cần thiết sau khi refactor.

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