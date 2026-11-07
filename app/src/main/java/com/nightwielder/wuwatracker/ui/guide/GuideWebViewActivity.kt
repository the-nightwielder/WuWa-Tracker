package com.nightwielder.wuwatracker.ui.guide

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

fun openKuroGuide(context: Context, url: String, name: String) {
    val intent = Intent(context, GuideWebViewActivity::class.java).apply {
        putExtra("guide_url", url)
        putExtra("character_name", name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

class GuideWebViewActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guideUrl = intent.getStringExtra("guide_url") ?: "https://wuwaguide.kurogames.com/en/"
        val characterName = intent.getStringExtra("character_name") ?: "Character"

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF16131D))) {
                    var isLoading by remember { mutableStateOf(true) }

                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                webChromeClient = WebChromeClient()
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                        val jsDismissModal = """
                                            (function() {
                                                var count = 0;
                                                var interval = setInterval(function() {
                                                    count++;
                                                    var elements = document.querySelectorAll('.login-modal, [class*="modal"], [class*="dialog"], [class*="login"]');
                                                    elements.forEach(function(el) {
                                                        var closeBtn = el.querySelector('[class*="close"], .btn-close, .close-btn, button');
                                                        if (closeBtn) { closeBtn.click(); }
                                                    });
                                                    if (count > 10) clearInterval(interval);
                                                }, 500);
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(jsDismissModal, null)
                                    }
                                }
                                loadUrl(guideUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Header overlay with Character Name & Close Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(12.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xCC16131D),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "📖 Official $characterName Build Guide",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD994),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        Button(
                            onClick = { finish() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xDDFFD994), contentColor = Color(0xFF16131D)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("✕ Exit Guide", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFFFFD994),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}
