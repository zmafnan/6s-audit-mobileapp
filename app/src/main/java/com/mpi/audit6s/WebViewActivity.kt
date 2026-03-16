package com.mpi.audit6s

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    // Permission request codes
    private val CAMERA_PERMISSION_REQUEST = 100
    private val STORAGE_PERMISSION_REQUEST = 101

    // Download manager
    private lateinit var downloadManager: DownloadManager
    private var downloadReceiver: BroadcastReceiver? = null

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

    // Variabel untuk menyimpan tanggal audit
    private var currentAuditDate: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)

        // Initialize download manager
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL

        // Request storage permissions immediately if needed
        requestStoragePermissionIfNeeded()

        setupWebView()
        webView.loadUrl(url)

        // Register download complete receiver
        registerDownloadCompleteReceiver()
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST
            )
        }
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
                val url = request?.url?.toString() ?: ""

                // Log all URL loads for debugging
                Log.d("WebViewClient", "Loading URL: $url")

                // Check if this is a PDF or download URL
                if (url.endsWith(".pdf") ||
                    url.contains("pdf") ||
                    url.contains("download") ||
                    url.contains("report") ||
                    url.contains("audit-report-pdf")) {

                    Log.d("WebViewClient", "Detected PDF URL: $url")

                    // Handle the download directly
                    downloadFile(url)

                    // Prevent WebView from loading the PDF
                    return true
                }

                return false // Let WebView handle all other URLs
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE

                // Inject JavaScript to intercept form submissions and button clicks
                view?.evaluateJavascript("""
    (function() {
        if (window.downloadInterceptorInstalled) return;
        window.downloadInterceptorInstalled = true;
        
        console.log('Installing download interceptors');
        
        // Fungsi untuk mengekstrak informasi tanggal audit
        window.extractAuditDate = function() {
            let auditDate = "";
            
            // Coba ekstrak tanggal audit dari berbagai sumber
            // 1. Coba dari input tanggal
            const dateInputs = document.querySelectorAll('input[type="date"], input[name*="date"], input[id*="date"], input[name*="tanggal"], input[id*="tanggal"]');
            for (const input of dateInputs) {
                if (input.value) {
                    auditDate = input.value;
                    console.log("Found audit date from input:", auditDate);
                    break;
                }
            }
            
            // 2. Coba dari elemen dengan label tanggal
            if (!auditDate) {
                const dateElements = document.querySelectorAll('[id*="date"], [class*="date"], [id*="tanggal"], [class*="tanggal"]');
                for (const elem of dateElements) {
                    const text = elem.textContent || "";
                    if (text.match(/\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{2,4}/)) {
                        auditDate = text.match(/\d{1,2}[\/\-\.]\d{1,2}[\/\-\.]\d{2,4}/)[0];
                        console.log("Found audit date from element:", auditDate);
                        break;
                    }
                }
            }
            
            // 3. Coba dari URL
            if (!auditDate) {
                const url = window.location.href.toLowerCase();
                if (url.includes('date=') || url.includes('tanggal=')) {
                    let dateParam = '';
                    if (url.includes('date=')) {
                        dateParam = url.split('date=')[1].split('&')[0];
                    } else {
                        dateParam = url.split('tanggal=')[1].split('&')[0];
                    }
                    
                    if (dateParam) {
                        auditDate = decodeURIComponent(dateParam);
                        console.log("Found audit date from URL:", auditDate);
                    }
                }
            }
            
            // Jika tanggal masih kosong, gunakan tanggal hari ini
            if (!auditDate) {
                const today = new Date();
                auditDate = today.getFullYear() + "-" + 
                            String(today.getMonth() + 1).padStart(2, '0') + "-" + 
                            String(today.getDate()).padStart(2, '0');
                console.log("Using today's date:", auditDate);
            }
            
            console.log("Final extracted audit date:", auditDate);
            
            // Kirim informasi ke Android
            AndroidDownloader.setAuditDate(auditDate);
            
            return auditDate;
        };
        
        // Add blob URL handler
        window.handleBlobDownload = function(blobUrl, filename) {
            console.log('Handling blob download: ' + blobUrl);
            
            // Ekstrak tanggal audit sebelum download
            window.extractAuditDate();
            
            fetch(blobUrl)
                .then(response => response.blob())
                .then(blob => {
                    // Create a reader to read the blob as data URL
                    const reader = new FileReader();
                    reader.onloadend = function() {
                        // The result is a data URL representing the file's data
                        const base64data = reader.result.split(',')[1];
                        // Send the base64 data to Android
                        AndroidDownloader.downloadBase64(base64data, filename || "download.pdf");
                    };
                    reader.readAsDataURL(blob);
                })
                .catch(error => {
                    console.error('Error handling blob:', error);
                });
        };
        
        // Intercept form submissions
        const originalSubmit = HTMLFormElement.prototype.submit;
        HTMLFormElement.prototype.submit = function() {
            console.log('Form submitted');
            
            // Ekstrak tanggal audit sebelum submit
            window.extractAuditDate();
            
            // Check if this is an audit form
            if (this.action && this.action.includes('audit')) {
                console.log('Audit form submitted, watching for PDF response');
                
                // Set a flag to watch for PDF after form submission
                window.watchForPdfAfterSubmit = true;
                
                // Set a timeout to check for PDF download
                setTimeout(function() {
                    // Look for download links that might have appeared
                    const links = document.querySelectorAll('a[href*="pdf"], a[href*="download"], a[href*="report"]');
                    links.forEach(function(link) {
                        console.log('Found potential PDF link after form submission:', link.href);
                        
                        // Check if this is a blob URL
                        if (link.href.startsWith('blob:')) {
                            window.handleBlobDownload(link.href, "audit_report.pdf");
                        } else {
                            AndroidDownloader.downloadFile(link.href);
                        }
                    });
                }, 3000); // Check after 3 seconds
            }
            
            return originalSubmit.apply(this, arguments);
        };
        
        // Intercept button clicks
        document.addEventListener('click', function(e) {
            const target = e.target;
            
            // Check if this is a submit button
            if (target.type === 'submit' || 
                target.tagName === 'BUTTON' || 
                (target.tagName === 'INPUT' && target.type === 'submit')) {
            
                console.log('Submit button clicked');
            
                // Ekstrak tanggal audit sebelum klik
                window.extractAuditDate();
            
                // Find the closest form
                const form = target.closest('form');
                if (form && form.action && form.action.includes('audit')) {
                    console.log('Audit form button clicked, watching for PDF response');
                
                    // Set a flag to watch for PDF after form submission
                    window.watchForPdfAfterSubmit = true;
                }
            }
            
            // Check for download links
            if (target.tagName === 'A' || target.parentElement.tagName === 'A') {
                const link = target.tagName === 'A' ? target : target.parentElement;
                const href = link.getAttribute('href');
            
                if (href && (
                    href.includes('pdf') || 
                    href.includes('download') || 
                    href.includes('report') ||
                    href.startsWith('blob:')
                )) {
                    console.log('Download link clicked:', href);
                    e.preventDefault();
                
                    // Ekstrak tanggal audit sebelum download
                    window.extractAuditDate();
                
                    // Check if this is a blob URL
                    if (href.startsWith('blob:')) {
                        window.handleBlobDownload(href, "audit_report.pdf");
                    } else {
                        AndroidDownloader.downloadFile(href);
                    }
                    return false;
                }
            }
        }, true);
        
        // Watch for new elements that might be download links
        const observer = new MutationObserver(function(mutations) {
            if (!window.watchForPdfAfterSubmit) return;
            
            mutations.forEach(function(mutation) {
                if (mutation.addedNodes) {
                    mutation.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) { // Element node
                            // Look for download links
                            const links = node.querySelectorAll('a[href*="pdf"], a[href*="download"], a[href*="report"], a[href^="blob:"]');
                            links.forEach(function(link) {
                                console.log('Found potential PDF link in new content:', link.href);
                                
                                // Check if this is a blob URL
                                if (link.href.startsWith('blob:')) {
                                    window.handleBlobDownload(link.href, "audit_report.pdf");
                                } else {
                                    AndroidDownloader.downloadFile(link.href);
                                }
                            });
                            
                            // Look for download buttons
                            const buttons = node.querySelectorAll('button, .btn, [role="button"]');
                            buttons.forEach(function(button) {
                                if (
                                    button.textContent.toLowerCase().includes('download') ||
                                    button.className.toLowerCase().includes('download')
                                ) {
                                    console.log('Found download button in new content');
                                    button.addEventListener('click', function() {
                                        console.log('Download button clicked');
                                    });
                                }
                            });
                        }
                    });
                }
            });
        });
        
        // Start observing
        observer.observe(document.body, { childList: true, subtree: true });
        
        // Intercept fetch API for PDF responses
        const originalFetch = window.fetch;
        window.fetch = function(url, options) {
            const promise = originalFetch.apply(this, arguments);
            
            // If we're watching for PDFs after form submission
            if (window.watchForPdfAfterSubmit) {
                return promise.then(response => {
                    const contentType = response.headers.get('content-type');
                    const contentDisposition = response.headers.get('content-disposition');
                    
                    console.log('Response content type:', contentType);
                    console.log('Content disposition:', contentDisposition);
                    
                    if (
                        (contentType && contentType.includes('pdf')) ||
                        (contentDisposition && contentDisposition.includes('attachment'))
                    ) {
                        console.log('PDF or attachment detected in response');
                        
                        // Clone the response to get the URL
                        response.clone().blob().then(blob => {
                            const url = window.URL.createObjectURL(blob);
                            console.log('Created blob URL for download:', url);
                            
                            // Extract filename from content-disposition
                            let filename = 'download.pdf';
                            if (contentDisposition) {
                                const filenameMatch = contentDisposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/);
                                if (filenameMatch && filenameMatch[1]) {
                                    filename = filenameMatch[1].replace(/['"]/g, '');
                                }
                            }
                            
                            // Handle the blob URL
                            window.handleBlobDownload(url, filename);
                        });
                    }
                    
                    return response;
                });
            }
            
            return promise;
        };
        
        console.log('Download interceptors installed successfully');
    })();
""", null)

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

            // Handle console messages for debugging
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                message?.let {
                    Log.d("WebView Console", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return super.onConsoleMessage(message)
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

        // Add JavaScript interface to help with downloads
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun downloadFile(url: String) {
                Log.d("JavascriptInterface", "Download requested for URL: $url")
                runOnUiThread {
                    // Start download using DownloadManager
                    downloadFile(url)
                }
            }

            @JavascriptInterface
            fun downloadBase64(base64Data: String, filename: String) {
                Log.d("JavascriptInterface", "Download requested for base64 data, filename: $filename")
                runOnUiThread {
                    // Convert base64 to file and download
                    downloadFromBase64(base64Data, filename)
                }
            }

            @JavascriptInterface
            fun setAuditDate(auditDate: String) {
                Log.d("JavascriptInterface", "Audit date received: $auditDate")
                runOnUiThread {
                    currentAuditDate = auditDate.replace("[^0-9\\-]".toRegex(), "_").trim()

                    // Format tanggal jika perlu
                    if (currentAuditDate.matches("\\d{4}-\\d{2}-\\d{2}".toRegex())) {
                        // Konversi dari YYYY-MM-DD ke DD-MM-YYYY jika diperlukan
                        val parts = currentAuditDate.split("-")
                        if (parts.size == 3) {
                            currentAuditDate = "${parts[2]}-${parts[1]}-${parts[0]}"
                        }
                    }

                    Log.d("AuditInfo", "Set audit date: $currentAuditDate")
                }
            }
        }, "AndroidDownloader")

        // Set up download listener for PDF downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            Log.d("DownloadListener", "Download requested: $url, mime: $mimeType")
            downloadFile(url)
        }
    }

    // Download file using Android's DownloadManager
    private fun downloadFile(url: String) {
        try {
            Log.d("DownloadManager", "Starting download for: $url")

            // Check for storage permission for older Android versions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_REQUEST
                )

                // Save the URL to download after permission is granted
                pendingDownloadUrl = url
                return
            }

            // Buat nama file dengan tanggal
            val dateStr = if (currentAuditDate.isNotEmpty()) currentAuditDate else {
                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
            }

            val filename = "Audit_report_${dateStr}.pdf"
            Log.d("DownloadManager", "Generated filename: $filename")

            // Create download request
            val request = DownloadManager.Request(Uri.parse(url))

            // Add description and notification visibility
            request.setDescription("Downloading audit report")
            request.setTitle(filename)

            // Show notification when download is complete
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            // Set destination to Downloads folder
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

            // Add cookie if needed
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(url)
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie)
            }

            // Add common headers that might be needed for authentication
            request.addRequestHeader("User-Agent", webView.settings.userAgentString)

            // Get download service and enqueue request
            val downloadId = downloadManager.enqueue(request)

            // Save the download ID for tracking
            currentDownloadId = downloadId

            // Show toast message
            Toast.makeText(this, "Downloading $filename", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            // Log the error
            Log.e("DownloadManager", "Download error: ${e.message}", e)
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Download file from base64 data
    private fun downloadFromBase64(base64Data: String, filename: String) {
        try {
            Log.d("DownloadManager", "Starting download from base64 data for: $filename")

            // Check for storage permission for older Android versions
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_REQUEST
                )

                // We can't save the base64 data easily for later, so just show an error
                Toast.makeText(this, "Storage permission required to download files", Toast.LENGTH_LONG).show()
                return
            }

            // Buat nama file dengan tanggal
            val dateStr = if (currentAuditDate.isNotEmpty()) currentAuditDate else {
                SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
            }

            val finalFilename = "Audit_report_${dateStr}.pdf"
            Log.d("DownloadManager", "Generated filename from base64: $finalFilename")

            // Decode base64 data
            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

            // Save directly to Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val file = File(downloadsDir, finalFilename)
            file.writeBytes(decodedBytes)

            // Make the file visible in Downloads app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, use MediaStore
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, finalFilename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }

                contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else {
                // For older Android versions, use MediaScanner
                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaScanIntent.data = Uri.fromFile(file)
                sendBroadcast(mediaScanIntent)
            }

            // Show success message
            Toast.makeText(this, "Downloaded $finalFilename to Downloads folder", Toast.LENGTH_LONG).show()

            // Try to open the file
            try {
                val fileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }

                val openIntent = Intent(Intent.ACTION_VIEW)
                openIntent.setDataAndType(fileUri, "application/pdf")
                openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                openIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)

                // Check if there's an app that can handle this intent
                if (openIntent.resolveActivity(packageManager) != null) {
                    startActivity(openIntent)
                } else {
                    Toast.makeText(this, "No PDF viewer app found, but file was saved to Downloads", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("DownloadManager", "Error opening file: ${e.message}", e)
                Toast.makeText(this, "File saved to Downloads folder, but couldn't open it: ${e.message}", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            // Log the error
            Log.e("DownloadManager", "Download error: ${e.message}", e)
            Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Register broadcast receiver for download completion
    private fun registerDownloadCompleteReceiver() {
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (currentDownloadId == id) {
                    // Check download status
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)

                        val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        val localUri = if (localUriIndex >= 0) cursor.getString(localUriIndex) else null

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            // Get the file path
                            val filePath = if (localUri != null) {
                                Uri.parse(localUri).path
                            } else {
                                "Download complete"
                            }

                            Toast.makeText(context, "Download complete: $filePath", Toast.LENGTH_LONG).show()

                            // Try to open the file
                            if (localUri != null) {
                                try {
                                    // Get the file from the URI
                                    val fileUri = Uri.parse(localUri)
                                    val file = File(fileUri.path ?: "")

                                    // Create a content URI using FileProvider
                                    val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        FileProvider.getUriForFile(
                                            context,
                                            "${packageName}.fileprovider",
                                            file
                                        )
                                    } else {
                                        fileUri
                                    }

                                    // Create intent to open the file
                                    val openIntent = Intent(Intent.ACTION_VIEW)
                                    openIntent.setDataAndType(contentUri, "application/pdf")
                                    openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    openIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)

                                    // Check if there's an app that can handle this intent
                                    if (openIntent.resolveActivity(packageManager) != null) {
                                        startActivity(openIntent)
                                    } else {
                                        Toast.makeText(context, "No PDF viewer app found, but file was saved to Downloads", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e("DownloadManager", "Error opening file: ${e.message}", e)
                                    Toast.makeText(context, "File saved to Downloads folder, but couldn't open it: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                            Toast.makeText(context, "Download failed: reason $reason", Toast.LENGTH_LONG).show()
                            Log.e("DownloadManager", "Download failed with reason code: $reason")
                        }
                    }
                    cursor.close()
                }
            }
        }

        // Register the receiver
        downloadReceiver = onComplete

        // Fix for Android 13+ (API 33+): Use RECEIVER_NOT_EXPORTED flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
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
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, reload the page to trigger the camera request again
                    webView.reload()
                } else {
                    // Permission denied
                    Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_LONG).show()
                }
            }
            STORAGE_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, try the download again if there's a pending URL
                    pendingDownloadUrl?.let {
                        downloadFile(it)
                        pendingDownloadUrl = null
                    }
                } else {
                    // Permission denied
                    Toast.makeText(this, "Storage permission is required to download files", Toast.LENGTH_LONG).show()
                }
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

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the download receiver
        downloadReceiver?.let {
            unregisterReceiver(it)
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val DEFAULT_URL = "about:blank"

        // Track current download
        private var currentDownloadId: Long = -1
        private var pendingDownloadUrl: String? = null
    }
}
