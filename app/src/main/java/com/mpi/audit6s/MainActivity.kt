package com.mpi.audit6s

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

/**
 * Main activity that displays the audit type selection screen.
 * Contains two options: Production Audit and Non-Production Audit.
 */
class MainActivity : AppCompatActivity(), View.OnClickListener {

    // URLs for the different audit types
    private val PRODUCTION_AUDIT_URL = "https://192.168.16.5:5174/6S/production-audit/create"
    private val NON_PRODUCTION_AUDIT_URL = "https://192.168.16.5:5174/6S/non-production-audit/create"

    // UI elements
    private lateinit var productionCard: CardView
    private lateinit var nonProductionCard: CardView
    private lateinit var productionButton: Button
    private lateinit var nonProductionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        initializeViews()

        // Set click listeners
        setClickListeners()
    }

    /**
     * Initialize all the views from the layout
     */
    private fun initializeViews() {
        productionCard = findViewById(R.id.card_production)
        nonProductionCard = findViewById(R.id.card_non_production)
        productionButton = findViewById(R.id.btn_production)
        nonProductionButton = findViewById(R.id.btn_non_production)
    }

    /**
     * Set click listeners for buttons and cards
     */
    private fun setClickListeners() {
        // Set this activity as the click listener for all clickable elements
        productionCard.setOnClickListener(this)
        nonProductionCard.setOnClickListener(this)
        productionButton.setOnClickListener(this)
        nonProductionButton.setOnClickListener(this)
    }

    /**
     * Handle click events for all clickable elements
     */
    override fun onClick(view: View) {
        when (view.id) {
            // Production audit clicks
            R.id.card_production, R.id.btn_production -> {
                openAuditWebView(PRODUCTION_AUDIT_URL)
            }

            // Non-production audit clicks
            R.id.card_non_production, R.id.btn_non_production -> {
                openAuditWebView(NON_PRODUCTION_AUDIT_URL)
            }
        }
    }

    /**
     * Open the WebView activity with the specified URL
     */
    private fun openAuditWebView(url: String) {
        val intent = Intent(this, WebViewActivity::class.java)
        intent.putExtra(WebViewActivity.EXTRA_URL, url)
        startActivity(intent)
    }
}
