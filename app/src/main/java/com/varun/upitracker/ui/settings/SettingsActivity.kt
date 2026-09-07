package com.varun.upitracker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.varun.upitracker.R
import com.varun.upitracker.ui.AliasMappingsActivity
import com.varun.upitracker.ui.CategorySettingsActivity
import com.varun.upitracker.ui.statement.StatementImportActivity
import com.varun.upitracker.ui.theme.padRootForSystemBars

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        padRootForSystemBars(R.id.main)

        findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener { finish() }
        findViewById<View>(R.id.cardCategories).setOnClickListener {
            startActivity(Intent(this, CategorySettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardImportStatement).setOnClickListener {
            startActivity(Intent(this, StatementImportActivity::class.java))
        }
        findViewById<View>(R.id.cardAccounts).setOnClickListener {
            startActivity(Intent(this, AccountsActivity::class.java))
        }
        findViewById<View>(R.id.cardFriendAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_FRIEND))
        }
        findViewById<View>(R.id.cardMerchantAliases).setOnClickListener {
            startActivity(AliasMappingsActivity.createIntent(this, AliasMappingsActivity.MODE_MERCHANT))
        }
    }
}
