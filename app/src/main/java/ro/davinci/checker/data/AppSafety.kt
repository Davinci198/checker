package ro.davinci.checker.data

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Cât de sigur e să oprești o aplicație.
 *
 * Clasificarea nu ghicește: se bazează pe rolurile pe care sistemul le dă
 * pachetului (lansator, tastatură, telefon, SMS, accesibilitate, administrator
 * de dispozitiv) și pe faptul că are sau nu un serviciu în prim-plan.
 */
enum class StopSafety(val label: String, val short: String) {
    SAFE("Poți opri în siguranță", "oprire sigură"),
    CAUTION("Oprește doar temporar", "atenție"),
    NEVER("Nu opri", "critic"),
}

data class SafetyVerdict(
    val level: StopSafety,
    val reason: String,
)

/**
 * Rolurile „critice” ale telefonului, citite o singură dată per scanare.
 *
 * Toate vin din API-uri publice, fără permisiuni suplimentare; pentru
 * serviciile de accesibilitate se citește cheia din `Settings.Secure`, exact
 * ce raportează și `settings get secure enabled_accessibility_services`.
 */
class SafetyAdvisor(private val context: Context) {

    data class Roles(
        val launcher: String? = null,
        val keyboard: String? = null,
        val dialer: String? = null,
        val sms: String? = null,
        val accessibility: Set<String> = emptySet(),
        val admins: Set<String> = emptySet(),
    )

    private val pm: PackageManager = context.packageManager

    fun roles(): Roles = Roles(
        launcher = defaultLauncher(),
        keyboard = securePackages("default_input_method").firstOrNull(),
        dialer = defaultDialer(),
        sms = defaultSms(),
        accessibility = securePackages("enabled_accessibility_services"),
        admins = deviceAdmins(),
    )

    fun evaluate(app: AppEntry, roles: Roles): SafetyVerdict {
        if (app.packageName == context.packageName) {
            return SafetyVerdict(StopSafety.NEVER, "Este chiar aplicația Checker.")
        }
        if (app.uid < SafetyAdvisor.FIRST_APP_UID) {
            return SafetyVerdict(
                StopSafety.NEVER,
                "Rulează cu UID de sistem (${app.uid}); oprirea ei poate destabiliza telefonul.",
            )
        }
        if (app.packageName == "android" || app.packageName == "com.android.systemui") {
            return SafetyVerdict(
                StopSafety.NEVER,
                "Face parte din interfața de bază a telefonului.",
            )
        }
        if (app.isPersistent) {
            return SafetyVerdict(
                StopSafety.NEVER,
                "Este un proces persistent de sistem: Android îl repornește imediat.",
            )
        }

        when (app.packageName) {
            roles.launcher -> return SafetyVerdict(
                StopSafety.NEVER,
                "Este lansatorul (ecranul principal). Oprirea lasă telefonul fără ecran de start.",
            )
            roles.keyboard -> return SafetyVerdict(
                StopSafety.NEVER,
                "Este tastatura implicită. Fără ea nu mai poți scrie în nicio aplicație.",
            )
            roles.dialer -> return SafetyVerdict(
                StopSafety.NEVER,
                "Este aplicația implicită pentru apeluri.",
            )
            roles.sms -> return SafetyVerdict(
                StopSafety.NEVER,
                "Este aplicația implicită pentru SMS.",
            )
        }

        if (app.packageName in roles.accessibility) {
            return SafetyVerdict(
                StopSafety.NEVER,
                "Are un serviciu de accesibilitate activ; oprirea îl dezactivează.",
            )
        }
        if (app.packageName in roles.admins) {
            return SafetyVerdict(
                StopSafety.NEVER,
                "Este administrator de dispozitiv activ " +
                    "(politici de securitate, ștergere de la distanță).",
            )
        }

        if (app.hasForegroundService) {
            return SafetyVerdict(
                StopSafety.CAUTION,
                "Are un serviciu în prim-plan (muzică, descărcare, navigație, VPN). " +
                    "Oprirea îl întrerupe, dar aplicația poate fi repornită.",
            )
        }
        if (app.isSystem) {
            return SafetyVerdict(
                StopSafety.CAUTION,
                "Aplicație de sistem. De obicei poate fi oprită, dar alte funcții " +
                    "s-ar putea să o repornească.",
            )
        }
        return SafetyVerdict(
            StopSafety.SAFE,
            "Aplicație de utilizator, fără serviciu în prim-plan. " +
                "Procesele ei se pot opri fără să afecteze telefonul.",
        )
    }

    // ------------------------------------------------------------- roluri

    private fun defaultLauncher(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let { return it }

        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo?.packageName
        }.getOrNull()
    }

    private fun defaultDialer(): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun defaultSms(): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    private fun deviceAdmins(): Set<String> = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.activeAdmins
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /**
     * Pachetele dintr-o cheie `Settings.Secure` de tipul
     * „pachet/serviciu:pachet/serviciu” (`enabled_accessibility_services`)
     * sau „pachet/serviciu” (`default_input_method`).
     */
    private fun securePackages(key: String): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, key)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: return emptySet()

        return raw.split(':', '\n')
            .map { it.trim().substringBefore('/') }
            .filter { it.isNotBlank() }
            .toSet()
    }

    companion object {
        /** Primul UID alocat aplicațiilor; sub el sunt procese de sistem. */
        const val FIRST_APP_UID = 10_000
    }
}