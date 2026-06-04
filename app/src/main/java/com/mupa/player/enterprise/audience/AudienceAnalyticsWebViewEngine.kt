package com.mupa.player.enterprise.audience

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class AudienceAnalyticsWebViewEngine(
    private val context: Context,
    private val modelsDir: File,
) {
    private var webView: WebView? = null
    private val pending = HashMap<String, CompletableDeferred<AudienceFrameResult>>()
    private var pageLoaded: CompletableDeferred<Unit>? = null

    suspend fun init(): Boolean {
        if (webView != null) return true

        val tf = File(modelsDir, "libs/tf.min.js")
        val faceApi = File(modelsDir, "libs/face-api.min.js")
        if (!tf.exists() || !faceApi.exists()) return false

        val loaded = CompletableDeferred<Unit>()
        pageLoaded = loaded

        withContext(Dispatchers.Main) {
            val wv = WebView(context)
            webView = wv

            @SuppressLint("SetJavaScriptEnabled")
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.settings.mediaPlaybackRequiresUserGesture = false

            wv.addJavascriptInterface(Bridge(), "AndroidAudience")

            wv.webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url ?: return null
                        if (url.scheme != "https" || url.host != "mplayer.local") return null
                        val path = url.encodedPath ?: return null
                        val file =
                            when {
                                path.startsWith("/models/") -> File(modelsDir, path.removePrefix("/models/"))
                                path.startsWith("/libs/") -> File(modelsDir, path.removePrefix("/"))
                                else -> null
                            } ?: return null

                        if (!file.exists()) return null
                        val mime =
                            when (file.extension.lowercase()) {
                                "js" -> "application/javascript"
                                "json" -> "application/json"
                                "bin" -> "application/octet-stream"
                                else -> "application/octet-stream"
                            }
                        return WebResourceResponse(mime, "utf-8", FileInputStream(file))
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoaded?.complete(Unit)
                    }
                }

            val html = buildHtml()
            wv.loadDataWithBaseURL("https://mplayer.local/", html, "text/html", "utf-8", null)
        }

        loaded.await()
        return true
    }

    suspend fun processFrameJpegBase64(base64Jpeg: String): AudienceFrameResult = withContext(Dispatchers.Main) {
        val wv = webView ?: throw IllegalStateException("not_initialized")
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<AudienceFrameResult>()
        pending[requestId] = deferred
        wv.evaluateJavascript("window.__processFrame('$requestId', '$base64Jpeg');", null)
        deferred.await()
    }

    suspend fun release() = withContext(Dispatchers.Main) {
        webView?.destroy()
        webView = null
        pending.clear()
        pageLoaded = null
    }

    private fun buildHtml(): String {
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <script src="/libs/tf.min.js"></script>
              <script src="/libs/face-api.min.js"></script>
            </head>
            <body>
              <script>
                (function(){
                  function fnv1a(str) {
                    var h = 0x811c9dc5;
                    for (var i = 0; i < str.length; i++) {
                      h ^= str.charCodeAt(i);
                      h = (h * 0x01000193) >>> 0;
                    }
                    return ('00000000' + h.toString(16)).slice(-8);
                  }

                  function ageRange(age) {
                    if (age == null) return null;
                    if (age < 18) return "0-17";
                    if (age < 25) return "18-24";
                    if (age < 35) return "25-34";
                    if (age < 45) return "35-44";
                    if (age < 55) return "45-54";
                    if (age < 65) return "55-64";
                    return "65+";
                  }

                  function isLooking(landmarks, maxDeg) {
                    try {
                      var leftEye = landmarks.getLeftEye();
                      var rightEye = landmarks.getRightEye();
                      var nose = landmarks.getNose();
                      if (!leftEye || !rightEye || !nose) return false;
                      var le = leftEye[0];
                      var re = rightEye[3];
                      var nx = nose[3].x;
                      var midX = (le.x + re.x) / 2.0;
                      var eyeDist = Math.abs(re.x - le.x);
                      if (eyeDist < 1) return false;
                      var ratio = Math.abs(nx - midX) / eyeDist;
                      var approxYawDeg = ratio * 90.0;
                      return approxYawDeg < maxDeg;
                    } catch(e) {
                      return false;
                    }
                  }

                  var initialized = false;
                  async function init() {
                    if (initialized) return true;
                    await faceapi.nets.tinyFaceDetector.loadFromUri('/models');
                    await faceapi.nets.ageGenderNet.loadFromUri('/models');
                    await faceapi.nets.faceLandmark68Net.loadFromUri('/models');
                    await faceapi.nets.faceRecognitionNet.loadFromUri('/models');
                    initialized = true;
                    return true;
                  }

                  window.__processFrame = async function(requestId, base64Jpeg) {
                    try {
                      await init();
                      var img = new Image();
                      img.src = 'data:image/jpeg;base64,' + base64Jpeg;
                      await new Promise(function(resolve, reject){
                        img.onload = resolve;
                        img.onerror = reject;
                      });

                      var detections = await faceapi
                        .detectAllFaces(img, new faceapi.TinyFaceDetectorOptions())
                        .withFaceLandmarks()
                        .withFaceDescriptors()
                        .withAgeAndGender();

                      var faces = [];
                      for (var i = 0; i < detections.length; i++) {
                        var d = detections[i];
                        var desc = d.descriptor || [];
                        var reduced = '';
                        for (var j = 0; j < desc.length; j += 8) {
                          var v = Math.round((desc[j] + 1) * 50);
                          reduced += String.fromCharCode(v);
                        }
                        var hash = fnv1a(reduced);

                        var age = (d.age != null) ? Math.round(d.age) : null;
                        var gender = d.gender || null;
                        var conf = (d.genderProbability != null) ? d.genderProbability : null;
                        var looking = isLooking(d.landmarks, 25);

                        faces.push({
                          faceHash: hash,
                          estimatedAge: age,
                          ageRange: ageRange(age),
                          gender: gender,
                          confidence: conf,
                          isLooking: looking
                        });
                      }

                      AndroidAudience.onResult(requestId, JSON.stringify({faces: faces}));
                    } catch(e) {
                      AndroidAudience.onError(requestId, '' + e);
                    }
                  };
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(requestId: String, json: String) {
            val deferred = pending.remove(requestId) ?: return
            deferred.complete(parseResult(json))
        }

        @JavascriptInterface
        fun onError(requestId: String, error: String) {
            val deferred = pending.remove(requestId) ?: return
            deferred.complete(
                AudienceFrameResult(
                    faces = emptyList(),
                ),
            )
        }
    }

    private fun parseResult(json: String): AudienceFrameResult {
        val root = JSONObject(json)
        val arr = root.optJSONArray("faces") ?: JSONArray()
        val out = ArrayList<DetectedFace>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += DetectedFace(
                faceHash = o.optString("faceHash", ""),
                estimatedAge = o.optInt("estimatedAge", -1).takeIf { it >= 0 },
                ageRange = o.optString("ageRange", "").takeIf { it.isNotBlank() },
                gender = o.optString("gender", "").takeIf { it.isNotBlank() },
                confidence = o.optDouble("confidence", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                isLooking = o.optBoolean("isLooking", false),
            )
        }
        return AudienceFrameResult(out.filter { it.faceHash.isNotBlank() })
    }
}
