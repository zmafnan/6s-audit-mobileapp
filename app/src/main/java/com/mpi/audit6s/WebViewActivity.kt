package com.mpi.audit6s

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // File upload callback
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // Permission request code
    private val CAMERA_PERMISSION_REQUEST = 100

    // Activity result launcher for file chooser
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            var results: Array<Uri>? = null

            // Check if the result has data
            if (data != null && data.dataString != null) {
                val dataString = data.dataString
                results = arrayOf(Uri.parse(dataString))
            } else {
                // If there's no data, check if we have a camera photo path
                cameraPhotoPath?.let {
                    val photoFile = File(it)
                    if (photoFile.exists()) {
                        val photoUri = FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        results = arrayOf(photoUri)
                    }
                }
            }

            // Send the result back to the WebView
            filePathCallback?.onReceiveValue(results ?: arrayOf())
            filePathCallback = null
        } else {
            // User cancelled the file picker, send null result
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)

        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL

        setupWebView()
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun setupWebView() {
        // Configure WebView client
        webView.webViewClient = object : WebViewClient() {
            // Bypass SSL errors
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                super.onPageFinished(view, url)
            }
        }

        // Configure WebChromeClient for file uploads and camera access
        webView.webChromeClient = object : WebChromeClient() {
            // Handle progress updates
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
                super.onProgressChanged(view, newProgress)
            }

            // Handle file uploads (including camera)
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Save the callback
                this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                this@WebViewActivity.filePathCallback = filePathCallback

                // Create camera intent
                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    // Create a file to save the image
                    var photoFile: File? = null
                    try {
                        photoFile = createImageFile()
                        takePictureIntent.putExtra("PhotoPath", cameraPhotoPath)
                    } catch (ex: IOException) {
                        // Error occurred while creating the File
                        Toast.makeText(this@WebViewActivity, "Error creating image file", Toast.LENGTH_SHORT).show()
                    }

                    // Continue only if the file was successfully created
                    if (photoFile != null) {
                        cameraPhotoPath = photoFile.absolutePath
                        val photoURI = FileProvider.getUriForFile(
                            this@WebViewActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    } else {
                        takePictureIntent = null
                    }
                }

                // Create file chooser intent
                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentSelectionIntent.type = "image/*"

                // Create chooser intent
                val intentArray = takePictureIntent?.let { arrayOf(it) } ?: arrayOfNulls<Intent>(0)
                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Select Image")
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)

                // Check for camera permission
                if (ContextCompat.checkSelfPermission(this@WebViewActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                    // Request camera permission
                    ActivityCompat.requestPermissions(
                        this@WebViewActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        CAMERA_PERMISSION_REQUEST
                    )
                    return true
                }

                // Launch the chooser
                fileChooserLauncher.launch(chooserIntent)
                return true
            }

            // Handle permission requests from web page (for camera access via getUserMedia)
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val permissions = request?.resources
                    if (permissions?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true) {
                        // Check camera permission
                        if (ContextCompat.checkSelfPermission(this@WebViewActivity, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.resources)
                        } else {
                            // Request camera permission
                            ActivityCompat.requestPermissions(
                                this@WebViewActivity,
                                arrayOf(Manifest.permission.CAMERA),
                                CAMERA_PERMISSION_REQUEST
                            )
                        }
                    } else {
                        request?.grant(request.resources)
                    }
                }
            }
        }

        // Configure WebView settings
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        // Enable camera and microphone access
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // Handle SSL and mixed content
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Enable debugging
        WebView.setWebContentsDebuggingEnabled(true)
    }

    // Create a file to store the camera image
    @Throws(IOException::class)
    private fun createImageFile(): File {
        // Create an image file name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        )
    }

    // Handle permission request results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, reload the page to trigger the camera request again
                webView.reload()
            } else {
                // Permission denied
                Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val DEFAULT_URL = "about:blank"
    }
}
