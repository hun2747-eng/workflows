package hu.dkc.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        private val OWN_HOSTS = setOf("dkc.hu", "www.dkc.hu")
    }

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offline: LinearLayout
    private lateinit var accountContainer: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var account: AccountPane

    @Volatile private var currentHostIsOwn = false
    private var suppressNav = false
    private var lastAdminUrl: String? = null
    private var adminLoggedIn: Boolean
        get() = getSharedPreferences("dkc_prefs", MODE_PRIVATE).getBoolean("admin_logged_in", false)
        set(v) { getSharedPreferences("dkc_prefs", MODE_PRIVATE).edit().putBoolean("admin_logged_in", v).apply() }
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    // Helymeghatározás a weboldalnak (pl. Új ügyfél → "Helymeghatározás" gomb)
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res.values.any { it }
        geoCallback?.invoke(geoOrigin, granted, false)
        geoCallback = null
        geoOrigin = null
        if (!granted) Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
    }

    private var afterPermission: ((Boolean) -> Unit)? = null
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        afterPermission?.invoke(granted)
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        offline = findViewById(R.id.offline)
        accountContainer = findViewById(R.id.accountContainer)
        bottomNav = findViewById(R.id.bottomNav)

        setupInsets()
        setupWebView()
        account = AccountPane(this, accountContainer)
        PushHelper.createChannel(this)
        checkLinkHandling()

        bottomNav.setOnItemSelectedListener { item ->
            if (!suppressNav) when (item.itemId) {
                R.id.nav_home -> showHome()
                R.id.nav_account -> showAccount()
                R.id.nav_admin -> showAdmin()
                R.id.nav_new_client -> web.loadUrl(getString(R.string.new_client_url))
                R.id.nav_calendar -> showCalendar()
                R.id.nav_menu -> { openWebMenu(); return@setOnItemSelectedListener false }
            }
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            if (!suppressNav) when (item.itemId) {
                R.id.nav_home -> web.loadUrl(BuildConfig.HOME_URL)
                R.id.nav_admin -> web.loadUrl(lastAdminUrl ?: getString(R.string.admin_url))
                R.id.nav_account -> if (!prefs.isLoggedIn) web.loadUrl(getString(R.string.register_url))
                R.id.nav_new_client -> web.loadUrl(getString(R.string.new_client_url))
                R.id.nav_calendar -> web.loadUrl(getString(R.string.calendar_url))
                R.id.nav_menu -> openWebMenu()
            }
        }
        updateAccountLabel()
        updateNav(null)

        findViewById<View>(R.id.retry).setOnClickListener {
            offline.isVisible = false
            web.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    accountContainer.isVisible -> {
                        accountContainer.isVisible = false
                        updateNav(web.url)
                    }
                    web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
            if (savedInstanceState.getBoolean("account_visible")) bottomNav.selectedItemId = R.id.nav_account
        } else {
            val handled = handleIntent(intent)
            if (web.url == null) web.loadUrl(BuildConfig.HOME_URL)
            if (!handled && prefs.isLoggedIn) PushHelper.syncToken(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
        outState.putBoolean("account_visible", accountContainer.isVisible)
    }

    // ---------------------------------------------------------------- deep links

    /** @return igaz, ha deep linket dolgoztunk fel */
    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        // Push értesítésből érkező URL (előtérben: EXTRA_OPEN_URL, háttérben az FCM data "url" kulcsa)
        val pushUrl = intent.getStringExtra(EXTRA_OPEN_URL) ?: intent.getStringExtra("url")
        if (pushUrl != null) {
            val u = Uri.parse(pushUrl)
            if (DeepLink.isAppPath(u)) return handleDeepLink(u)
            if (isOwn(u)) { showHomeTab(); web.loadUrl(pushUrl) }
            return false
        }
        if (intent.action == Intent.ACTION_VIEW) return handleDeepLink(intent.data)
        return false
    }

    fun handleDeepLink(uri: Uri?): Boolean {
        when (val link = DeepLink.parse(uri)) {
            is DeepLink.Login -> {
                prefs.token = link.uuid
                prefs.referralCode = null
                updateAccountLabel()
                Toast.makeText(this, R.string.login_ok, Toast.LENGTH_SHORT).show()
                bottomNav.selectedItemId = R.id.nav_account
                account.render(forceReload = true)
                PushHelper.syncToken(this)
                return true
            }
            is DeepLink.Referral -> {
                if (prefs.isLoggedIn) {
                    Toast.makeText(this, R.string.ref_saved_logged_in, Toast.LENGTH_LONG).show()
                } else {
                    prefs.referralCode = link.ref
                    bottomNav.selectedItemId = R.id.nav_account
                    account.render()
                }
                return true
            }
            null -> {
                if (DeepLink.isAppPath(uri)) {
                    // /app/... paraméter nélkül – a fiók fülre visszük
                    bottomNav.selectedItemId = R.id.nav_account
                    return true
                }
                return false
            }
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun showHome() {
        accountContainer.isVisible = false
        val u = web.url
        if (u == null || isAdminUrl(u) || isRegisterUrl(u)) web.loadUrl(BuildConfig.HOME_URL)
    }

    private fun showAdmin() {
        accountContainer.isVisible = false
        val u = web.url
        if (!isAdminUrl(u) || isNewClientUrl(u) || isCalendarUrl(u)) web.loadUrl(lastAdminUrl ?: getString(R.string.admin_url))
    }

    private fun showCalendar() {
        accountContainer.isVisible = false
        if (!isCalendarUrl(web.url)) web.loadUrl(getString(R.string.calendar_url))
    }

    /** Az admin oldal saját (hamburger) menüjének megnyitása. */
    private fun openWebMenu() {
        accountContainer.isVisible = false
        val js = """
            (function(){
              var hm = document.querySelector('[data-dkc-menu]');
              if (hm) {
                hm.click();
                setTimeout(function(){ var p = hm.parentElement; if (p) p.scrollIntoView({block:'start', behavior:'smooth'}); }, 80);
                return 'ok';
              }
              function vis(e){ return e && e.offsetParent !== null && !e.disabled; }
              var sels = ['[data-drawer-toggle]','[data-drawer-target]','[data-collapse-toggle]',
                '[aria-controls*="menu" i]','[aria-controls*="sidebar" i]','[aria-controls*="nav" i]',
                '[aria-label*="menu" i]','[aria-label*="menü" i]','[title*="menü" i]','[title*="menu" i]',
                '#menu-toggle','#menuToggle','#sidebar-toggle','#sidebarToggle','#mobile-menu-button','#hamburger',
                '.menu-toggle','.hamburger','.sidebar-toggle','.navbar-toggler','.nav-toggle'];
              for (var i = 0; i < sels.length; i++) {
                var l = document.querySelectorAll(sels[i]);
                for (var j = 0; j < l.length; j++) if (vis(l[j])) { l[j].click(); return 'ok'; }
              }
              var b = document.querySelectorAll('button, a, [role=button]');
              for (var k = 0; k < b.length; k++) {
                var p = b[k].querySelector('svg path');
                var d = p ? (p.getAttribute('d') || '') : '';
                var t = (b[k].textContent || '').trim();
                if (vis(b[k]) && (/M4 6h16M4 12h16M4 18h16|M3 12h18M3 6h18M3 18h18|M4 6h16M4 12h16m-7 6h7/i.test(d) || t === '☰' || /^men[uü]$/i.test(t))) { b[k].click(); return 'ok'; }
              }
              return 'none';
            })()
        """.trimIndent()
        web.evaluateJavascript(js) { res ->
            if (res != "\"ok\"") {
                web.scrollTo(0, 0)
                Toast.makeText(this, R.string.menu_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Admin oldalon az appban felesleges gombok elrejtése (a weboldal "+ Új ügyfél" és "Menü" gombja
     * – ezek az alsó sávban vannak), és a Naptár gomb sárga számának kiolvasása.
     * A "Menü" gomb csak rejtve van (data-dkc-menu), az alsó sáv Menü ikonja ezt kattintja.
     */
    private val adminTidyJs = """
        (function(){
          function norm(t){ return (t || '').replace(/\s+/g, ' ').trim(); }
          function tidy(){
            var els = document.querySelectorAll('a, button, [role=button], summary');
            for (var i = 0; i < els.length; i++) {
              var el = els[i], t = norm(el.textContent);
              if (/^\+?\s*Új ügyfél$/i.test(t)) {
                el.style.setProperty('display', 'none', 'important');
                el.setAttribute('data-dkc-hidden', '1');
              } else if (/^[☰≡]?\s*Men[uü]$/i.test(t)) {
                el.style.setProperty('display', 'none', 'important');
                el.setAttribute('data-dkc-menu', '1');
              }
            }
          }
          tidy();
          if (!window.__dkcObs) {
            window.__dkcObs = new MutationObserver(function(){ clearTimeout(window.__dkcT); window.__dkcT = setTimeout(tidy, 50); });
            window.__dkcObs.observe(document.documentElement, { childList: true, subtree: true });
          }
          var c = -1, l = document.querySelectorAll('a, button');
          for (var j = 0; j < l.length; j++) {
            var h = l[j].getAttribute('href') || '', m = norm(l[j].textContent).match(/^Naptár\s*(\d+)?$/i);
            if (m || h.indexOf('/admin/naptar') >= 0 && /^Naptár/i.test(norm(l[j].textContent))) {
              var d = norm(l[j].textContent).match(/(\d+)$/);
              c = d ? parseInt(d[1], 10) : 0;
              break;
            }
          }
          return c;
        })()
    """.trimIndent()

    private fun tidyAdminPage(view: WebView) {
        view.evaluateJavascript(adminTidyJs) { res ->
            val n = res?.trim('"')?.toDoubleOrNull()?.toInt() ?: return@evaluateJavascript
            if (n >= 0) { calendarCount = n; applyCalendarBadge() }
        }
    }

    private var calendarCount = 0

    /** Sárga szám a Naptár ikonon (mint a weboldalon). */
    private fun applyCalendarBadge() {
        if (bottomNav.menu.findItem(R.id.nav_calendar) == null) return
        if (calendarCount > 0) {
            bottomNav.getOrCreateBadge(R.id.nav_calendar).apply {
                number = calendarCount
                backgroundColor = getColor(R.color.badge_yellow)
                badgeTextColor = Color.BLACK
                isVisible = true
            }
        } else {
            bottomNav.removeBadge(R.id.nav_calendar)
        }
    }

    private fun pathOf(url: String?): String? {
        val u = url?.let { Uri.parse(it) } ?: return null
        return if (isOwn(u)) (u.path ?: "/").trimEnd('/') else null
    }

    private fun isAdminUrl(url: String?) = pathOf(url)?.startsWith("/admin") == true
    private fun isNewClientUrl(url: String?) = pathOf(url) == "/admin/ugyfel/uj"
    private fun isCalendarUrl(url: String?) = pathOf(url)?.startsWith("/admin/naptar") == true
    private fun isRegisterUrl(url: String?) = pathOf(url)?.startsWith("/klimat-szeretnek") == true

    private fun selectTab(id: Int) {
        if (bottomNav.selectedItemId == id) return
        suppressNav = true
        bottomNav.selectedItemId = id
        suppressNav = false
    }

    /**
     * Alsó sáv:
     *  - alap: Főoldal · Regisztráció · Admin
     *  - admin bejelentkezve: Admin · + Új ügyfél · Naptár · Menü
     *  - admin, az Új ügyfél oldalon: Admin · Naptár · Menü
     */
    private fun updateNav(url: String?) {
        val admin = adminLoggedIn
        applyNavMenu(admin)
        bottomNav.menu.findItem(R.id.nav_new_client)?.isVisible = !isNewClientUrl(url)
        val want = when {
            accountContainer.isVisible -> R.id.nav_account
            admin && isCalendarUrl(url) -> R.id.nav_calendar
            isAdminUrl(url) || admin -> R.id.nav_admin
            isRegisterUrl(url) && !prefs.isLoggedIn -> R.id.nav_account
            else -> R.id.nav_home
        }
        selectTab(want)
    }

    /** Admin oldalon: ha van jelszómező, az a belépő oldal (nincs bejelentkezve). */
    private fun checkAdminState(view: WebView, url: String?) {
        if (!isAdminUrl(url)) { updateNav(url); return }
        if (pathOf(url)?.startsWith("/admin/logout") == true) {
            adminLoggedIn = false; lastAdminUrl = null; updateNav(url); return
        }
        if (pathOf(url)?.startsWith("/admin/elfelejtett-jelszo") == true) {
            adminLoggedIn = false; updateNav(url); return
        }
        // Belépő űrlap = jelszómező + "elfelejtett jelszó" link (a jelszóváltó oldal nem számít)
        view.evaluateJavascript(
            "!!document.querySelector('input[type=password]') && !!document.querySelector('a[href*=\"elfelejtett-jelszo\"]')"
        ) { res ->
            val loginPage = res == "true"
            adminLoggedIn = !loginPage
            if (!loginPage && !isNewClientUrl(url) && !isCalendarUrl(url)) lastAdminUrl = url
            if (loginPage) lastAdminUrl = null
            updateNav(url)
        }
    }

    private var navAdminMode: Boolean? = null

    /** Menücsere: vendég (Főoldal · Regisztráció · Admin) ↔ admin (Admin · Új ügyfél · Naptár · Menü). */
    private fun applyNavMenu(admin: Boolean) {
        if (navAdminMode == admin) return
        navAdminMode = admin
        suppressNav = true
        bottomNav.menu.clear()
        bottomNav.inflateMenu(if (admin) R.menu.bottom_nav_admin else R.menu.bottom_nav)
        suppressNav = false
        updateAccountLabel()
        applyCalendarBadge()
    }

    /** Kijelentkezve "Regisztráció", bejelentkezve "Fiókom". */
    fun updateAccountLabel() {
        bottomNav.menu.findItem(R.id.nav_account)?.title =
            getString(if (prefs.isLoggedIn) R.string.nav_account else R.string.nav_register)
    }

    private fun showHomeTab() {
        accountContainer.isVisible = false
    }

    private fun showAccount() {
        if (prefs.isLoggedIn || !prefs.referralCode.isNullOrBlank()) {
            // Bejelentkezett ügyfél: natív Fiókom; ajánlói linkkel érkező: natív regisztráció (referral_code miatt)
            accountContainer.isVisible = true
            account.render()
        } else {
            accountContainer.isVisible = false
            if (!isRegisterUrl(web.url)) web.loadUrl(getString(R.string.register_url))
        }
    }

    fun onLoggedOut() {
        updateAccountLabel()
        account.render()
    }

    // ---------------------------------------------------------------- dkc.hu linkek az appban

    /**
     * Android 12+: az e-mailben kapott https://dkc.hu/app/... linkeket csak akkor nyitja meg a rendszer
     * az appban, ha a domain ellenőrzött (assetlinks.json) vagy a felhasználó engedélyezte.
     * Ha egyik sem, egyszer (verziónként) felajánljuk a beállítás megnyitását.
     */
    private fun checkLinkHandling() {
        if (Build.VERSION.SDK_INT < 31) return
        val sp = getSharedPreferences("dkc_prefs", MODE_PRIVATE)
        if (sp.getInt("links_asked_version", 0) == BuildConfig.VERSION_CODE) return
        val ok = try {
            val m = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val st = m?.getDomainVerificationUserState(packageName)
            val s = st?.hostToStateMap?.get("dkc.hu")
            s == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED ||
                s == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
        } catch (_: Exception) { true }
        if (ok) return
        sp.edit().putInt("links_asked_version", BuildConfig.VERSION_CODE).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.links_title)
            .setMessage(R.string.links_text)
            .setPositiveButton(R.string.links_open_settings) { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton(R.string.links_later, null)
            .show()
    }

    // ---------------------------------------------------------------- push permission

    fun requestNotificationPermission(cb: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            cb(true); return
        }
        afterPermission = cb
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- layout

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else 0)
            bottomNav.isVisible = !imeVisible
            insets
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun isOwn(uri: Uri?) = uri?.host?.lowercase() in OWN_HOSTS

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        with(web.settings) {
            javaScriptEnabled = true
            setGeolocationEnabled(true)
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString DKCApp/${BuildConfig.VERSION_NAME} (Android)"
        }
        web.addJavascriptInterface(JsBridge(), "DKCApp")

        swipe.setColorSchemeResources(R.color.brand)
        swipe.setOnRefreshListener { web.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (DeepLink.isAppPath(uri)) {
                    handleDeepLink(uri)
                    return true
                }
                val scheme = uri.scheme?.lowercase()
                if ((scheme == "https" || scheme == "http") && isOwn(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                currentHostIsOwn = isOwn(url?.let { Uri.parse(it) })
                offline.isVisible = false
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (isAdminUrl(url)) tidyAdminPage(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                swipe.isRefreshing = false
                progress.isVisible = false
                CookieManager.getInstance().flush()
                checkAdminState(view, url)
                if (isAdminUrl(url)) tidyAdminPage(view)
                if (isOwn(url?.let { Uri.parse(it) }) && !isAdminUrl(url)) {
                    // Az appban nem kell az admin belépés link
                    view.evaluateJavascript(
                        "document.querySelectorAll('a[href*=\"/admin\"]').forEach(function(a){a.style.display='none'});",
                        null,
                    )
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    swipe.isRefreshing = false
                    offline.isVisible = true
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
                // Csak a saját (dkc.hu) oldalak kaphatnak helyadatot
                if (!isOwn(origin?.let { Uri.parse(it) })) { callback.invoke(origin, false, false); return }
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) { callback.invoke(origin, true, false); return }
                geoCallback?.invoke(geoOrigin, false, false)
                geoCallback = callback
                geoOrigin = origin
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }

            override fun onGeolocationPermissionsHidePrompt() {
                geoCallback = null
                geoOrigin = null
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.isVisible = newProgress < 100
                progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }
    }

    fun openExternal(uri: Uri) {
        try {
            val i = if (uri.scheme == "intent") Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, uri)
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(i)
        } catch (_: Exception) { }
    }

    fun openInWeb(url: String) {
        showHomeTab()
        web.loadUrl(url)
    }

    /**
     * JS híd a weboldal számára (window.DKCApp). Csak dkc.hu oldalakon ad vissza adatot.
     *   DKCApp.isApp()          -> true
     *   DKCApp.getToken()       -> app_uuid vagy ""
     *   DKCApp.getReferralCode()-> ajánlói kód vagy ""
     */
    inner class JsBridge {
        @JavascriptInterface fun isApp(): Boolean = true
        @JavascriptInterface fun platform(): String = "android"
        @JavascriptInterface fun getToken(): String = if (currentHostIsOwn) prefs.token ?: "" else ""
        @JavascriptInterface fun getReferralCode(): String = if (currentHostIsOwn) prefs.referralCode ?: "" else ""
        @JavascriptInterface fun openAccount() { runOnUiThread { bottomNav.selectedItemId = R.id.nav_account } }
    }

    fun confirm(msg: Int, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setPositiveButton(R.string.yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}
