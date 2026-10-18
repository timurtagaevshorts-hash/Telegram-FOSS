package uz.kodlikino.top4.ui

import android.app.Activity
import android.os.Bundle
import org.telegram.ui.LaunchActivity

/**
 * YuzGram #69: shaffof host — bildirdi-chnyochi yoki qulf ekranidagi
 * tugma bosilganda to'liq ekranli plerni ochadi. LaunchActivity allaqachon
 * yuqorida bo'lsa, pler shu yerga fragment bo'lib qo'yiladi va host o'zi
 * darhol yopiladi (foydalanuvchi oynasi orqaga qaytmaydi).
 */
class YuzPlayerHostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = LaunchActivity.instance
        if (launch != null && !launch.isFinishing) {
            launch.runOnUiThread {
                runCatching { launch.presentFragment(YuzPlayerActivity()) }
            }
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // Ilova yopiq — asosiy ekran orqali qayta ochamiz; plerni
        // foydalanuvchi keyin tanlaydi (sessionActivity bunda yaxshiroq).
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
