package jetzy.theme;

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

enum class NightMode {
    SYSTEM,
    LIGHT,
    DARK;
    
    @Composable
    fun isDark(): Boolean {
        return when (this) {
            SYSTEM -> isSystemInDarkTheme()
            LIGHT -> false
            DARK -> true
        }
    }
}