# MabrookTrack Android SDK

Install attribution and in-app events for TikTok app campaigns, by [MabrookTrack](https://mabrooktrack.com/#mmp). minSdk 24, Kotlin.

## Install

`settings.gradle.kts` → `dependencyResolutionManagement.repositories`: add `maven { url = uri("https://jitpack.io") }`

`app/build.gradle.kts`:

```kotlin
implementation("com.github.MabrookTrackGitHut.mabrooktrack-android:sdk:0.1.0")
```

## Use

```kotlin
import com.mabrooktrack.sdk.*

// Application.onCreate()
MabrookTrack.configure(this, MabrookConfig(appKey = "<APP KEY FROM YOUR DASHBOARD>", locale = "ar-SA"))

// after login
MabrookTrack.setUser(email = email, phone = phone, externalId = customerId)

// events
MabrookTrack.viewContent(mapOf("product_id" to "SKU-1"))
MabrookTrack.addToCart(mapOf("product_id" to "SKU-1", "value" to 199))
MabrookTrack.trackPurchase(MabrookPurchase(value = 199.0, currency = "SAR", orderId = order.id))
MabrookTrack.generateLead(mapOf("form" to "contact"))
MabrookTrack.track("any_custom_event", mapOf("k" to "v"))
```

The install is sent automatically on first launch, with the Google Play Install Referrer for deterministic attribution. The advertising id is read via Play Services and omitted when the user limited ad tracking; the `AD_ID` permission is declared by the SDK.

Privacy: email/phone hashed at MabrookTrack's edge; `MabrookTrack.setConsent(MabrookConsent.DENIED)` stops all sends.

The full step-by-step guide, with the event mapping to TikTok, is in your MabrookTrack dashboard.
