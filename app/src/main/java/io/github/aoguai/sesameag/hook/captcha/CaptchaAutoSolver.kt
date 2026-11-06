package io.github.aoguai.sesameag.hook.captcha

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.github.aoguai.sesameag.hook.PageStructureSnapshotter
import io.github.aoguai.sesameag.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * H5 滑块自动求解器（默认不启用）。
 *
 * 实测环境（2026-11-06，支付宝 12.12.50.8000）：
 * - 验证页宿主：`com.alipay.mobile.nebulax.xriver.activity.XRiverTransActivity$Main`
 * - 渲染容器：`com.alipay.mywebview.sdk_shell.MYWebView$WebViewEx`
 *   → `com.alipay.mywebview.sdk.WebView` → `FrameLayout`（**不是** android.webkit.WebView）
 * - `evaluateJavascript` 存在，但回调类型是 SDK 自己的 `com.alipay.mywebview.sdk.ValueCallback`
 * - 验证码是 JShield：`.dv_handler` 把手 / `#slide_root_view` 滑轨 / `.jshield-captcha-puzzle-container` 图区
 * - 背景与拼图块走 `mdn.alipayobjects.com/netflow_captcha/...`（CDN 直链，无需鉴权）
 *
 * 只在 [io.github.aoguai.sesameag.model.BaseModel.captchaAutoSolve] 打开时被调用；
 * 失败必须立刻放弃（连续重试会显著抬高账号风险等级）。
 */
object CaptchaAutoSolver {
    private const val TAG = "CaptchaAutoSolver"
    private const val LOGCAT_TAG = "SesameLog"
    private const val VERIFY_DELAY_MS = 1_500L
    private const val MIN_ATTEMPT_INTERVAL_MS = 60_000L

    private val running = AtomicBoolean(false)
    private val capabilityLogged = AtomicBoolean(false)
    private val jsErrorLogged = AtomicBoolean(false)

    @Volatile
    private var lastAttemptAtMs: Long = 0L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 定位滑块的 JS（JShield 结构）。
     *
     * ```
     * #captcha-container .captcha-container
     *   .jshield-captcha-puzzle-container.puzzle      ← 背景/拼图区
     *   #c_puzzle_wrapper_view.slider-container
     *     #slide_root_view.dv_drag_verify             ← 滑轨
     *       .dv_handler.baseslider                    ← 可拖动把手
     * ```
     */
    private val locateJs: String = """
        (function(){
          function pick(selectors){
            for(var i=0;i<selectors.length;i++){
              var list=document.querySelectorAll(selectors[i]);
              for(var j=0;j<list.length;j++){
                var r=list[j].getBoundingClientRect();
                if(r.width>0&&r.height>0) return {el:list[j],sel:selectors[i],r:r};
              }
            }
            return null;
          }
          var handle=pick(['.dv_handler','#slide_root_view .dv_handler','#nc_1_n1z','[id*="n1z"]',
                           '[class*="btn_slide"]','[class*="slider-button"]','[class*="sliderBtn"]']);
          var track=pick(['#slide_root_view','.dv_drag_verify','.slider-container']);
          if(!handle&&!track) return JSON.stringify({found:false,url:location.href,title:document.title});
          var h=handle||track;
          var puzzle=pick(['.jshield-captcha-puzzle-container','.jshield-captcha-component',
                           '#captcha-container','[class*="puzzle"]','[class*="bg"]']);
          var bgSrc='', bgW=0, bgH=0;
          if(puzzle){
            bgW=puzzle.r.width; bgH=puzzle.r.height;
            if(puzzle.el.tagName==='IMG'){ bgSrc=puzzle.el.src||''; }
            else {
              var innerImg=puzzle.el.querySelector('img[src*="netflow_captcha"]')||puzzle.el.querySelector('img');
              if(innerImg){ bgSrc=innerImg.src||''; }
              if(!bgSrc){ var st=getComputedStyle(puzzle.el);
                var m=/url\("?([^")]+)"?\)/.exec(st.backgroundImage||''); if(m) bgSrc=m[1]; }
            }
          }
          var all=document.querySelectorAll('img'), imgs=[];
          for(var i=0;i<all.length&&imgs.length<8;i++){ if(all[i].src&&imgs.indexOf(all[i].src)<0) imgs.push(all[i].src); }
          // 收集所有 netflow_captcha 资源（背景 + 拼图块），拼图块用于 alpha 轮廓匹配
          var net=[];
          function addNet(u){ if(u&&u.indexOf('netflow_captcha')>=0&&net.indexOf(u)<0) net.push(u); }
          for(var i2=0;i2<all.length;i2++) addNet(all[i2].src);
          var cands=document.querySelectorAll('[class*=puzzle],[class*=piece],[class*=block],[class*=slider],[class*=drag]');
          for(var i3=0;i3<cands.length&&i3<20;i3++){
            var st2=getComputedStyle(cands[i3]);
            var m2=/url\("?([^")]+)"?\)/.exec(st2.backgroundImage||''); if(m2) addNet(m2[1]);
          }
          var detail=[], scope=puzzle?puzzle.el:document;
          var pit=scope.querySelectorAll('img');
          for(var j=0;j<pit.length&&detail.length<6;j++){
            var ir=pit[j].getBoundingClientRect();
            detail.push({src:pit[j].src||'',w:Math.round(ir.width),h:Math.round(ir.height),
              cls:(typeof pit[j].className==='string'?pit[j].className:'').slice(0,40)});
          }
          return JSON.stringify({found:true,sel:h.sel,handleCls:(handle?handle.el.className:''),
            x:h.r.left+h.r.width/2,y:h.r.top+h.r.height/2,w:h.r.width,hgt:h.r.height,
            trackX:(track?track.r.left:0), trackW:(track?track.r.width:0),
            cssW:window.innerWidth,cssH:window.innerHeight,dpr:window.devicePixelRatio||1,
            bgW:bgW,bgH:bgH,bgSrc:bgSrc,imgs:imgs,puzzleImgs:detail,netflow:net,url:location.href});
        })()
    """.trimIndent()

    /** 拖动后回读：进度条宽度能证明"触摸有没有被页面识别"。 */
    private val stateAfterJs: String = """
        (function(){
          var bar=document.querySelector('.dv_progress_bar');
          var h=document.querySelector('.dv_handler');
          var err=document.querySelector('[class*="captcha-tips"],[class*="error-tips"],[class*="jshield-captcha-tips"]');
          return JSON.stringify({
            barW: bar? Math.round(bar.getBoundingClientRect().width):-1,
            handleX: h? Math.round(h.getBoundingClientRect().left):-1,
            tips: err? (err.textContent||'').replace(/\s+/g,' ').slice(0,60):'',
            url: location.href
          });
        })()
    """.trimIndent()

    private val stillPresentJs: String = """
        (function(){
          var h=document.querySelector('.dv_handler')||document.querySelector('#slide_root_view')||
                document.querySelector('#nc_1_n1z')||document.querySelector('[class*="btn_slide"]');
          return JSON.stringify({present: !!h, url: location.href, title: document.title});
        })()
    """.trimIndent()

    /** 定位失败时的自诊断：把像滑块的元素和一段 DOM 抓回来。 */
    private val domDiagnosticsJs: String = """
        (function(){
          var out=[];
          var all=document.querySelectorAll('*');
          for(var i=0;i<all.length && out.length<60;i++){
            var e=all[i];
            var id=e.id||'';
            var cls=(typeof e.className==='string')?e.className:'';
            var key=(id+' '+cls).toLowerCase();
            if(key.indexOf('slide')>=0||key.indexOf('slider')>=0||key.indexOf('captcha')>=0||
               key.indexOf('verify')>=0||key.indexOf('n1z')>=0||key.indexOf('nc_')>=0||key.indexOf('drag')>=0){
              var r=e.getBoundingClientRect();
              out.push({tag:e.tagName,id:id,cls:cls.slice(0,80),
                x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)});
            }
          }
          var iframes=document.querySelectorAll('iframe');
          var frames=[]; for(var j=0;j<iframes.length;j++){ frames.push(iframes[j].src||''); }
          return JSON.stringify({count:out.length,items:out,frames:frames,url:location.href,
            html:(document.documentElement.outerHTML||'').slice(0,3000)});
        })()
    """.trimIndent()

    fun shouldAttempt(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < MIN_ATTEMPT_INTERVAL_MS) return false
        return running.compareAndSet(false, true)
    }

    /** 在后台尝试一次自动过滑块；调用方需先检查 [shouldAttempt]。 */
    fun solveOnce(reason: String) {
        lastAttemptAtMs = System.currentTimeMillis()
        thread(name = "captcha-solver") {
            try {
                runAttempt(reason)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "自动过滑块异常", t)
            } finally {
                running.set(false)
            }
        }
    }

    private fun runAttempt(reason: String) {
        // 验证页刚开始加载时 h5_trans_web_content 里只有 ProgressBar，WebView 还没创建。
        val webView = waitForWebView() ?: return log("等待超时仍没有 WebView，放弃")
        val webViewView = webView as? View
            ?: return log("WebView 宿主不是 View，放弃: ${webView.javaClass.name}")
        val activity = PageStructureSnapshotter.resumedActivity()
        log("开始尝试 reason=$reason activity=${activity?.javaClass?.name ?: "?"} webView=${webView.javaClass.name}")

        val located = locate(webView)
        if (located == null) {
            dumpDomDiagnostics(webView)
            return log("未定位到滑块把手，已 dump DOM 诊断")
        }
        val handleX = located.optDouble("x", -1.0)
        val handleY = located.optDouble("y", -1.0)
        val cssWidth = located.optDouble("cssW", 0.0)
        if (handleX < 0 || handleY < 0 || cssWidth <= 0) return log("滑块坐标非法，放弃")

        val images = loadImages(located) ?: return log("背景图不可用，放弃")
        val bitmap = images.background
        // 多个拼图块时逐个匹配，取置信度最高的结果
        val match = images.pieces
            .mapNotNull { piece ->
                runCatching { SliderCaptchaSolver.findGapByOutline(bitmap, piece) }.getOrNull()
                    ?.also { log("轮廓匹配 piece=${piece.width}x${piece.height} x=${it.offsetPx} score=${"%.1f".format(it.score)} refined=${it.refined}") }
            }
            .maxByOrNull { it.score }
        val gapPx = match?.offsetPx
            ?: SliderCaptchaSolver.findGapOffset(bitmap)?.also { log("缺口识别方式=列能量(降级) gapPx=$it") }
            ?: return log("未识别出缺口位置，放弃")
        if (match != null) {
            log("缺口识别方式=轮廓匹配${if (match.refined) "+全分辨率精修" else ""} gapPx=$gapPx score=${"%.1f".format(match.score)}")
        }

        val bgWidthCss = located.optDouble("bgW", 0.0).takeIf { it > 0 } ?: cssWidth
        val bgHeightCss = located.optDouble("bgH", 0.0)
        log(
            "缺口结果 bitmap=${bitmap.width}x${bitmap.height} gapPx=$gapPx pieces=${images.pieces.size} " +
                "bgCss=${bgWidthCss.toInt()}x${bgHeightCss.toInt()}",
        )

        val distanceCss = gapPx.toDouble() * (bgWidthCss / bitmap.width)
        val viewWidth = webViewView.width.toDouble().takeIf { it > 0 } ?: cssWidth
        val scale = viewWidth / cssWidth
        val distanceViewPx = (distanceCss * scale).toInt()
        if (distanceViewPx <= 0) return log("换算距离非法 distanceCss=$distanceCss scale=$scale")

        val startX = (handleX * scale).toFloat()
        val startY = (handleY * scale).toFloat()
        // 每次拖动的轨迹、节奏、停顿位置都随机；终点严格对齐缺口
        val plan = SliderCaptchaSolver.buildPlan(distanceViewPx, startY = 0f)
        log(
            "注入拖动 handleCss=(${handleX.toInt()},${handleY.toInt()}) scale=${"%.2f".format(scale)} " +
                "distanceCss=${"%.1f".format(distanceCss)} distanceView=$distanceViewPx " +
                SliderCaptchaSolver.describePlan(plan),
        )

        // 按下位置也随机偏移 ±3/±2px，避免每次都是同一个像素点
        val random = kotlin.random.Random(System.nanoTime())
        val jitterX = random.nextFloat() * 6f - 3f
        val jitterY = random.nextFloat() * 4f - 2f

        // 宿主是 SDK 的 FrameLayout 包装类；实测下钻到 EmbedViewContainer 后页面毫无反应，
        // 触摸必须交给 SDK 自己的触摸入口。
        val touchTarget = TouchTarget(webViewView, startX + jitterX, startY + jitterY)
        log("触摸目标 ${touchTarget.view.javaClass.name} at (${touchTarget.x.toInt()},${touchTarget.y.toInt()})")

        val done = java.util.concurrent.CountDownLatch(1)
        val dragStartedAt = System.currentTimeMillis()
        SliderDragInjector.drag(touchTarget.view, touchTarget.x, touchTarget.y, plan) { done.countDown() }
        done.await(15, TimeUnit.SECONDS)
        log("拖动耗时 ${System.currentTimeMillis() - dragStartedAt}ms")

        // 先快速回读一次状态（此时页面可能已判定失败并刷新验证码）
        Thread.sleep(700L)
        logAfterDrag(webView, located.optString("url"))
        Thread.sleep(1_200L)
        val passed = verify(webView)
        log(if (passed) "✅ 滑块疑似通过（DOM 中已无把手）" else "❌ 滑块未通过，停止重试")
    }

    /** 拖动后回读页面状态：进度条宽度 > 0 说明触摸确实被页面接收了。 */
    private fun logAfterDrag(
        target: Any,
        beforeUrl: String,
    ) {
        val json = evaluateJs(target, stateAfterJs, 3_000L)?.let(::parseJsJson)
            ?: return log("拖动后状态回读失败（JS 无返回）")
        val barW = json.optInt("barW")
        val url = json.optString("url")
        log(
            "拖动后 barW=$barW handleX=${json.optInt("handleX")} " +
                "tips=${json.optString("tips").ifBlank { "-" }} urlChanged=${url != beforeUrl}",
        )
        when {
            url != beforeUrl -> log("页面已更换验证码：判定未通过并刷新（触摸有效，需要调轨迹/距离）")
            barW == 0 -> log("触摸未生效：页面进度条宽度仍为 0，说明 MotionEvent 没进渲染层")
        }
    }

    private fun waitForWebView(timeoutMs: Long = 10_000L): Any? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastTreeLogged = false
        while (System.currentTimeMillis() < deadline) {
            val activity = PageStructureSnapshotter.resumedActivity()
            val target = findWebViewLike(activity?.window?.decorView)
            if (target != null) {
                val view = target as? View
                if (view == null || (view.width > 0 && view.height > 0)) {
                    logCapabilityOnce(target)
                    return target
                }
                Thread.sleep(150)
                if (view.width > 0 && view.height > 0) {
                    logCapabilityOnce(target)
                    return target
                }
                Thread.sleep(150)
                logCapabilityOnce(target)
                return target
            }
            if (!lastTreeLogged && System.currentTimeMillis() > deadline - timeoutMs / 2) {
                lastTreeLogged = true
                dumpViewTree(activity?.window?.decorView)
            }
            Thread.sleep(200)
        }
        return null
    }

    /**
     * 按类名找 WebView。
     *
     * `MYWebView$WebViewEx` 的父类是 `com.alipay.mywebview.sdk.WebView`（FrameLayout 子类），
     * 用 `is android.webkit.WebView` 判断会整棵树都匹配不到，所以只按名字找，再靠反射调用 JS。
     */
    private fun findWebViewLike(root: View?): Any? {
        if (root == null) return null
        if (root.javaClass.name.contains("WebView", ignoreCase = true)) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findWebViewLike(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun logCapabilityOnce(target: Any) {
        if (!capabilityLogged.compareAndSet(false, true)) return
        val chain = classChain(target.javaClass).joinToString(" -> ") { it.simpleName }
        val hasEvaluate = target.javaClass.methods.any {
            it.name == "evaluateJavascript" && it.parameterCount == 2
        }
        val innerWebViewFields = runCatching {
            classChain(target.javaClass)
                .flatMap { it.declaredFields.asSequence() }
                .filter { WebView::class.java.isAssignableFrom(it.type) }
                .joinToString(",") { it.name }
        }.getOrDefault("")
        val jsLikeMethods = runCatching {
            classChain(target.javaClass)
                .flatMap { it.declaredMethods.asSequence() }
                .map { it.name }
                .filter { name ->
                    val lower = name.lowercase()
                    lower.contains("javascript") || lower.contains("evaluate") || lower.contains("jsbridge") ||
                        lower.contains("runjs") || lower.contains("execjs")
                }
                .distinct()
                .take(12)
                .joinToString(",")
        }.getOrDefault("")
        log(
            "WebView 宿主 type=${target.javaClass.name} chain=$chain " +
                "evaluateJavascript=$hasEvaluate innerWebViewFields=[$innerWebViewFields] " +
                "jsLikeMethods=[$jsLikeMethods]",
        )
        logPluginDexPaths(target)
    }

    /** 打印目标类所在 ClassLoader 的 dex 路径，便于静态分析动态加载的插件类。 */
    private fun logPluginDexPaths(target: Any) {
        runCatching {
            val loader = target.javaClass.classLoader ?: return
            val pathList = classChain(loader.javaClass)
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == "pathList" }
                ?.apply { isAccessible = true }
                ?.get(loader) ?: return
            val dexElements = classChain(pathList.javaClass)
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == "dexElements" }
                ?.apply { isAccessible = true }
                ?.get(pathList) as? Array<*> ?: return
            val paths = dexElements.mapNotNull { element ->
                element ?: return@mapNotNull null
                classChain(element.javaClass)
                    .flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { it.name == "path" }
                    ?.apply { isAccessible = true }
                    ?.get(element)
                    ?.toString()
            }.filter { path ->
                val lower = path.lowercase()
                lower.contains("xriver") || lower.contains("nebula") || lower.contains("mywebview") ||
                    lower.contains("captcha") || lower.contains("plugin") || lower.contains("bundle")
            }.distinct().take(8)
            if (paths.isEmpty()) {
                log("插件 dex 路径: 未匹配到可疑路径（loader=${loader.javaClass.name}）")
            } else {
                paths.forEach { log("插件 dex 路径: $it") }
            }
        }.onFailure { Log.printStackTrace(TAG, "读取插件 dex 路径失败", it) }
    }

    /**
     * 统一 JS 调用入口。
     *
     * 目标可能不是标准 WebView，按三级降级：
     * 1. 真 WebView → evaluateJavascript；
     * 2. 反射同名方法（回调类型用动态代理适配 SDK 自己的 ValueCallback）；
     * 3. 反射内部字段里真正的 WebView。
     */
    private fun evaluateJs(
        target: Any,
        js: String,
        timeoutMs: Long = 5_000L,
    ): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var raw: String? = null
        Handler(Looper.getMainLooper()).post {
            val dispatched = runCatching {
                invokeEvaluate(target, js) { value ->
                    raw = value
                    latch.countDown()
                }
            }.getOrElse {
                if (jsErrorLogged.compareAndSet(false, true)) {
                    Log.printStackTrace(TAG, "JS 调用异常", it)
                } else {
                    log("JS 调用异常: ${it.javaClass.simpleName}: ${it.message}")
                }
                false
            }
            if (!dispatched) latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.SECONDS)
        return raw
    }

    private fun invokeEvaluate(
        target: Any,
        js: String,
        onResult: (String?) -> Unit,
    ): Boolean {
        if (target is WebView) {
            target.evaluateJavascript(js) { value -> onResult(value) }
            return true
        }
        val method = classChain(target.javaClass)
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "evaluateJavascript" && it.parameterCount == 2 }
        if (method != null) {
            method.isAccessible = true
            // 签名是 evaluateJavascript(String, com.alipay.mywebview.sdk.ValueCallback)，
            // 直接传 android.webkit.ValueCallback 会抛 IllegalArgumentException，必须按真实接口代理。
            val callbackType = method.parameterTypes[1]
            method.invoke(target, js, createValueCallbackProxy(callbackType, onResult))
            return true
        }
        val inner = classChain(target.javaClass)
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { WebView::class.java.isAssignableFrom(it.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(target) as? WebView
                }.getOrNull()
            }
        if (inner != null) {
            inner.evaluateJavascript(js) { value -> onResult(value) }
            return true
        }
        log("目标既不是 WebView 也没有可用的 evaluateJavascript: ${target.javaClass.name}")
        return false
    }

    /** 按目标接口动态实现回调；不同版本方法名可能是 onReceiveValue / onResult。 */
    private fun createValueCallbackProxy(
        callbackType: Class<*>,
        onResult: (String?) -> Unit,
    ): Any {
        val loader = callbackType.classLoader
            ?: CaptchaAutoSolver::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, args ->
            when {
                method.name.startsWith("onReceive") || method.name == "onResult" -> {
                    onResult(args?.firstOrNull() as? String)
                    defaultValue(method.returnType)
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "SesameValueCallback"
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private fun classChain(type: Class<*>): Sequence<Class<*>> =
        generateSequence<Class<*>>(type) { it.superclass }.take(8)

    private fun locate(target: Any): JSONObject? {
        val json = evaluateJs(target, locateJs)?.let(::parseJsJson) ?: return null
        if (!json.optBoolean("found", false)) {
            log("页面无滑块元素 url=${json.optString("url")}")
            return null
        }
        return json
    }

    private fun verify(target: Any): Boolean {
        val json = evaluateJs(target, stillPresentJs, 4_000L)?.let(::parseJsJson) ?: return false
        return !json.optBoolean("present", true)
    }

    /** 拖动后回读页面状态：进度条宽度 > 0 说明触摸确实被页面接收了。 */
    private fun logAfterDrag(target: Any) {
        val json = evaluateJs(target, stateAfterJs, 3_000L)?.let(::parseJsJson)
            ?: return log("拖动后状态回读失败（JS 无返回）")
        val barW = json.optInt("barW")
        log(
            "拖动后 barW=$barW handleX=${json.optInt("handleX")} " +
                "tips=${json.optString("tips").ifBlank { "-" }}",
        )
        if (barW == 0) {
            log("触摸未生效：页面进度条宽度仍为 0，说明 MotionEvent 没进渲染层（需要 JS 合成事件或换注入层）")
        }
    }

    private fun dumpDomDiagnostics(target: Any) {
        val raw = evaluateJs(target, domDiagnosticsJs) ?: return log("DOM 诊断无返回")
        val json = parseJsJson(raw) ?: return log("DOM 诊断解析失败")
        log("DOM 诊断 url=${json.optString("url")} candidates=${json.optInt("count")}")
        val frames = json.optJSONArray("frames")
        for (index in 0 until (frames?.length() ?: 0)) {
            log("DOM iframe[$index]=${frames?.optString(index)}")
        }
        val items = json.optJSONArray("items")
        for (index in 0 until minOf(items?.length() ?: 0, 40)) {
            val item = items?.optJSONObject(index) ?: continue
            log(
                "DOM 候选 <${item.optString("tag")}> id=${item.optString("id")} " +
                    "class=${item.optString("cls")} rect=(${item.optInt("x")},${item.optInt("y")}) " +
                    "${item.optInt("w")}x${item.optInt("h")}",
            )
        }
        val html = json.optString("html")
        if (html.isNotBlank()) {
            html.chunked(700).take(6).forEachIndexed { index, chunk ->
                log("DOM html[$index]=$chunk")
            }
        }
    }

    private fun parseJsJson(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val decoded = runCatching {
            JSONTokener(raw).nextValue() as? String
        }.getOrNull() ?: raw
        return runCatching { JSONObject(decoded) }.getOrNull()
    }

    private data class TouchTarget(
        val view: View,
        val x: Float,
        val y: Float,
    )

    private data class CaptchaImages(
        val background: Bitmap,
        val pieces: List<Bitmap>,
    )

    private fun loadImages(located: JSONObject): CaptchaImages? {
        val bgUrl = chooseBackgroundUrl(located) ?: return null
        val background = fetchBitmap(bgUrl) ?: return null
        val pieces = loadPieces(located, bgUrl)
        if (pieces.isEmpty()) log("未取到拼图块，缺口识别将退化为列能量法")
        return CaptchaImages(background, pieces)
    }

    /** 背景图：netflow_captcha 资源优先，且渲染宽度要接近图区宽度。 */
    private fun chooseBackgroundUrl(located: JSONObject): String? {
        data class Candidate(val url: String, val cssWidth: Int, val cssHeight: Int, val cls: String)

        val bgWidthCss = located.optDouble("bgW", 0.0)
        val candidates = mutableListOf<Candidate>()
        val detail = located.optJSONArray("puzzleImgs")
        for (index in 0 until (detail?.length() ?: 0)) {
            val item = detail?.optJSONObject(index) ?: continue
            val url = item.optString("src")
            if (!url.startsWith("http")) continue
            candidates += Candidate(url, item.optInt("w"), item.optInt("h"), item.optString("cls"))
        }
        val bgSrc = located.optString("bgSrc")
        if (bgSrc.startsWith("http") && candidates.none { it.url == bgSrc }) {
            candidates += Candidate(bgSrc, 0, 0, "bgSrc")
        }
        if (candidates.isEmpty()) return null

        candidates.forEach {
            log("候选图 ${it.cssWidth}x${it.cssHeight} cls=${it.cls.ifBlank { "-" }} ${it.url.substringAfterLast('/').take(24)}")
        }

        val scored = candidates.map { candidate ->
            var score = 0.0
            if (candidate.url.contains("netflow_captcha")) score += 100
            if (bgWidthCss > 0 && candidate.cssWidth > 0) {
                val ratio = candidate.cssWidth / bgWidthCss
                score += when {
                    ratio >= 0.85 -> 60.0
                    ratio >= 0.5 -> 20.0
                    else -> 0.0
                }
                if (candidate.cssHeight > 0) {
                    val bgRatio = candidate.cssWidth.toDouble() / candidate.cssHeight
                    val containerRatio = bgWidthCss / located.optDouble("bgH", 1.0).coerceAtLeast(1.0)
                    score += (10 - kotlin.math.abs(bgRatio - containerRatio) * 10).coerceAtLeast(0.0)
                }
            }
            score += (candidate.cssWidth * candidate.cssHeight) / 100_000.0
            candidate to score
        }.sortedByDescending { it.second }

        val best = scored.first()
        val chosen = best.first
        log("选定背景 ${chosen.cssWidth}x${chosen.cssHeight} score=${"%.1f".format(best.second)} captchaAsset=${chosen.url.contains("netflow_captcha")}")
        return chosen.url
    }

    /** 拼图块：netflow_captcha 资源里除背景外、接近正方形且不太大的那些（实测 58x58 带 alpha）。 */
    private fun loadPieces(
        located: JSONObject,
        backgroundUrl: String,
    ): List<Bitmap> {
        val urls = mutableListOf<String>()
        val net = located.optJSONArray("netflow")
        for (index in 0 until (net?.length() ?: 0)) {
            net?.optString(index)?.takeIf { it.startsWith("http") && it != backgroundUrl }?.let(urls::add)
        }
        val imgs = located.optJSONArray("imgs")
        for (index in 0 until (imgs?.length() ?: 0)) {
            val url = imgs?.optString(index).orEmpty()
            if (url.startsWith("http") && url != backgroundUrl && !urls.contains(url)) urls += url
        }
        val result = mutableListOf<Bitmap>()
        urls.take(4).forEach { url ->
            if (result.size >= 2) return@forEach
            val bitmap = fetchBitmap(url) ?: return@forEach
            val ratio = bitmap.width.toDouble() / bitmap.height.coerceAtLeast(1)
            if (ratio in 0.6..1.6 && bitmap.width in 24..200) {
                log("选定拼图块 ${bitmap.width}x${bitmap.height} ${url.substringAfterLast('/').take(20)}")
                result += bitmap
            } else {
                bitmap.recycle()
            }
        }
        return result
    }

    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.onFailure { Log.printStackTrace(TAG, "下载验证图失败: $url", it) }.getOrNull()

    /** 找不到 WebView 时把最终视图树打进日志。 */
    private fun dumpViewTree(root: View?) {
        if (root == null) {
            log("验证页视图树为空（无前台 Activity）")
            return
        }
        val builder = StringBuilder("验证页最终视图树\n")
        collectViews(root, builder, 0)
        log(builder.toString().trimEnd())
    }

    private fun collectViews(
        view: View,
        builder: StringBuilder,
        depth: Int,
    ) {
        if (depth > 10 || builder.length > 4_000) return
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return
        builder.append("  ".repeat(depth))
            .append(view.javaClass.name.substringAfterLast('.'))
            .append(" [").append(view.left).append(',').append(view.top).append(']')
            .append(' ').append(view.width).append('x').append(view.height)
            .append('\n')
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectViews(view.getChildAt(index), builder, depth + 1)
        }
    }

    private fun currentTaskLabel(): String =
        runCatching {
            io.github.aoguai.sesameag.task.TaskExecutionReport.current()?.taskLabel()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"

    private fun log(message: String) {
        Log.record(TAG, message)
        runCatching { android.util.Log.i(LOGCAT_TAG, "$TAG: $message") }
    }
}
