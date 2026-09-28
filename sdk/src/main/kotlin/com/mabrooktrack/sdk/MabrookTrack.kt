/*
 * MabrookTrack — native Android SDK (Kotlin)
 * Mobile measurement for TikTok app campaigns. Same wire contract as the
 * iOS and React Native packages: POST https://mmp.mabrooktrack.com/app/<appKey>.
 *
 * Privacy: the advertising id is omitted when the user limited ad tracking;
 * email and phone are hashed at MabrookTrack's edge; consent DENIED stops all sends.
 */
package com.mabrooktrack.sdk

import android.content.Context
import android.os.Build
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.pow

enum class MabrookConsent { GRANTED, DENIED, UNKNOWN }

data class MabrookConfig(
    /** App key from Dashboard → Agency → MMP → your app (one per platform build). */
    val appKey: String,
    /** BCP-47 locale of the user, e.g. "ar-SA". Improves TikTok matching. */
    val locale: String? = null,
    /** Start with consent already granted. */
    val consent: MabrookConsent? = null,
    val debug: Boolean = false,
    val endpoint: String = "https://mmp.mabrooktrack.com",
)

data class MabrookPurchase(
    val value: Double,
    val currency: String = "SAR",
    val orderId: String,
    val properties: Map<String, Any?>? = null,
)

object MabrookTrack {
    private const val TAG = "MabrookTrack"
    private const val PREFS = "mabrooktrack"

    private lateinit var app: Context
    private var config: MabrookConfig? = null
    private var consent = MabrookConsent.UNKNOWN
    private var anonId = ""
    private val sessionId = UUID.randomUUID().toString()
    private val traits = HashMap<String, String>()
    private var gaid: String? = null
    private var installReferrer: String? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** Call once at app start (Application.onCreate). Sends install on first launch, app_open afterwards. */
    @JvmStatic
    fun configure(context: Context, config: MabrookConfig) {
        app = context.applicationContext
        this.config = config
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        consent = config.consent ?: prefs.getString("consent", null)?.let { runCatching { MabrookConsent.valueOf(it) }.getOrNull() } ?: MabrookConsent.UNKNOWN
        if (config.consent != null) prefs.edit().putString("consent", consent.name).apply()
        anonId = prefs.getString("anon_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("anon_id", it).apply() }
        prefs.getString("traits", null)?.let { s ->
            runCatching { JSONObject(s) }.getOrNull()?.let { j -> j.keys().forEach { k -> traits[k] = j.getString(k) } }
        }
        val firstRun = !prefs.getBoolean("installed", false)
        executor.execute {
            readGaid()
            if (firstRun) {
                prefs.edit().putBoolean("installed", true).apply()
                readInstallReferrer { ref ->
                    installReferrer = ref
                    if (ref != null) prefs.edit().putString("install_referrer", ref).apply()
                    val body = JSONObject().put("event_type", "install")
                    if (ref != null) body.put("install_referrer", ref)
                    send(body)
                }
            } else {
                installReferrer = prefs.getString("install_referrer", null)
                send(JSONObject().put("event_type", "app_open").put("event_id", UUID.randomUUID().toString()))
            }
            log("configured firstRun=$firstRun consent=$consent gaid=${gaid != null}")
        }
    }

    /** Attach the logged-in shopper (hashed at the edge; raw never stored). */
    @JvmStatic
    fun setUser(email: String? = null, phone: String? = null, externalId: String? = null) {
        email?.let { traits["email"] = it }
        phone?.let { traits["phone"] = it }
        externalId?.let { traits["external_id"] = it }
        val j = JSONObject(); traits.forEach { (k, v) -> j.put(k, v) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("traits", j.toString()).apply()
    }

    /** DENIED stops all sends. */
    @JvmStatic
    fun setConsent(value: MabrookConsent) {
        consent = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("consent", value.name).apply()
    }

    /** Any event name. Standard names are mapped to TikTok's app events server-side. */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?>? = null) {
        val body = JSONObject().put("event_type", name).put("event_id", UUID.randomUUID().toString())
        if (properties != null) body.put("properties", JSONObject(properties))
        executor.execute { send(body) }
    }

    @JvmStatic @JvmOverloads fun viewContent(p: Map<String, Any?>? = null) = track("view_content", p)
    @JvmStatic @JvmOverloads fun search(query: String, p: Map<String, Any?>? = null) = track("search", (p ?: emptyMap()) + ("query" to query))
    @JvmStatic @JvmOverloads fun addToWishlist(p: Map<String, Any?>? = null) = track("add_to_wishlist", p)
    @JvmStatic @JvmOverloads fun addToCart(p: Map<String, Any?>? = null) = track("add_to_cart", p)
    @JvmStatic @JvmOverloads fun initiateCheckout(p: Map<String, Any?>? = null) = track("initiate_checkout", p)
    @JvmStatic @JvmOverloads fun addPaymentInfo(p: Map<String, Any?>? = null) = track("add_payment_info", p)
    @JvmStatic @JvmOverloads fun completeRegistration(p: Map<String, Any?>? = null) = track("complete_registration", p)
    @JvmStatic @JvmOverloads fun login(p: Map<String, Any?>? = null) = track("login", p)
    @JvmStatic @JvmOverloads fun startTrial(p: Map<String, Any?>? = null) = track("start_trial", p)
    @JvmStatic @JvmOverloads fun generateLead(p: Map<String, Any?>? = null) = track("lead", p)
    @JvmStatic @JvmOverloads fun rate(p: Map<String, Any?>? = null) = track("rate", p)
    @JvmStatic @JvmOverloads fun completeTutorial(p: Map<String, Any?>? = null) = track("complete_tutorial", p)
    @JvmStatic @JvmOverloads fun achieveLevel(level: Int, p: Map<String, Any?>? = null) = track("achieve_level", (p ?: emptyMap()) + ("level" to level))
    @JvmStatic @JvmOverloads fun unlockAchievement(p: Map<String, Any?>? = null) = track("unlock_achievement", p)
    @JvmStatic @JvmOverloads fun spendCredits(value: Double, p: Map<String, Any?>? = null) = track("spend_credits", (p ?: emptyMap()) + ("value" to value))

    /** Paid subscription started. Pass value/currency for value-based optimisation. */
    @JvmStatic
    @JvmOverloads
    fun subscribe(value: Double? = null, currency: String? = null, orderId: String? = null, properties: Map<String, Any?>? = null) {
        val body = JSONObject().put("event_type", "subscribe").put("event_id", UUID.randomUUID().toString())
        value?.let { body.put("value", it) }
        currency?.let { body.put("currency", it) }
        orderId?.let { body.put("order_id", it) }
        properties?.let { body.put("properties", JSONObject(it)) }
        executor.execute { send(body) }
    }

    /** The money event. Deduplicated server-side on orderId. */
    @JvmStatic
    fun trackPurchase(purchase: MabrookPurchase) {
        val body = JSONObject()
            .put("event_type", "purchase")
            .put("event_id", UUID.randomUUID().toString())
            .put("value", purchase.value)
            .put("currency", purchase.currency)
            .put("order_id", purchase.orderId)
        purchase.properties?.let { body.put("properties", JSONObject(it)) }
        executor.execute { send(body) }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun readGaid() {
        gaid = try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(app)
            if (info.isLimitAdTrackingEnabled) null else info.id
        } catch (_: Exception) { null }
    }

    private fun readInstallReferrer(cb: (String?) -> Unit) {
        try {
            val client = InstallReferrerClient.newBuilder(app).build()
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    val ref = try {
                        if (code == InstallReferrerClient.InstallReferrerResponse.OK) client.installReferrer.installReferrer else null
                    } catch (_: Exception) { null }
                    try { client.endConnection() } catch (_: Exception) {}
                    cb(ref)
                }
                override fun onInstallReferrerServiceDisconnected() { cb(null) }
            })
        } catch (_: Exception) { cb(null) }
    }

    private fun baseFields(): JSONObject {
        val j = JSONObject()
            .put("anon_id", anonId)
            .put("session_id", sessionId)
            .put("platform", "android")
            .put("os", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("model", Build.MODEL ?: "")
            .put("att", if (gaid != null) "authorized" else "denied")
            .put("consent", consent.name.lowercase())
            .put("ts", System.currentTimeMillis())
        gaid?.let { j.put("gaid", it) }
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()?.let { j.put("app_version", it) }
        (config?.locale ?: Locale.getDefault().toLanguageTag()).let { j.put("locale", it) }
        traits.forEach { (k, v) -> j.put(k, v) }
        return j
    }

    /** Runs on the executor. Up to 5 attempts with exponential backoff on 5xx / network errors. */
    private fun send(body: JSONObject) {
        val cfg = config ?: run { log("track before configure()"); return }
        if (consent == MabrookConsent.DENIED) { log("consent denied — dropped ${body.optString("event_type")}"); return }
        val payload = baseFields()
        body.keys().forEach { k -> payload.put(k, body.get(k)) }
        val url = URL(cfg.endpoint.trimEnd('/') + "/app/" + cfg.appKey)
        for (attempt in 0 until 5) {
            val status = try {
                (url.openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"
                    connectTimeout = 10_000; readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    OutputStreamWriter(outputStream).use { it.write(payload.toString()) }
                    responseCode.also { disconnect() }
                }
            } catch (e: Exception) { -1 }
            if (status in 200..299) { log("sent ${body.optString("event_type")} $status"); return }
            if (status in 400..499) { log("rejected ${body.optString("event_type")} $status"); return }
            Thread.sleep(min(30_000.0, 500.0 * 2.0.pow(attempt)).toLong())
        }
        log("send failed after retries ${body.optString("event_type")}")
    }

    private fun log(s: String) { if (config?.debug == true) Log.i(TAG, s) }
}
