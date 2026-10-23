package ch.fitapp.privat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * Liest Daten aus Health Connect. Samsung Health schreibt Schritte, Schlaf, Puls und Trainings dort hinein.
 * FitApp liest nur, schreibt nie etwas zurück, und gibt die Werte als JSON an die Webseite weiter.
 * Antworten gehen an window.fitHealthStatus(json) und window.fitHealthDaten(json).
 */
class HealthBridge(private val activity: Activity, private val web: WebView) {

    companion object {
        const val REQ = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uhr: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val arten: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        SleepSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        ActiveCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class
    )
    private val rechte: Set<String> = arten.map { HealthPermission.getReadPermission(it) }.toSet()

    private fun sdk(): Int = try {
        HealthConnectClient.getSdkStatus(activity)
    } catch (e: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    private fun senden(funktion: String, o: JSONObject) {
        val js = "window.$funktion && window.$funktion(" + JSONObject.quote(o.toString()) + ")"
        web.post { web.evaluateJavascript(js, null) }
    }

    /** Status an die Seite: sdk ok, update oder fehlt, dazu wie viele Datenarten erlaubt sind. */
    fun pruefen() {
        scope.launch { senden("fitHealthStatus", status()) }
    }

    private suspend fun status(): JSONObject {
        val o = JSONObject()
        o.put("von", rechte.size)
        when (sdk()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                o.put("sdk", "ok")
                val erlaubt: Set<String> = try {
                    HealthConnectClient.getOrCreate(activity).permissionController.getGrantedPermissions()
                } catch (e: Exception) {
                    emptySet()
                }
                o.put("anzahl", rechte.count { it in erlaubt })
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                o.put("sdk", "update")
                o.put("anzahl", 0)
            }
            else -> {
                o.put("sdk", "fehlt")
                o.put("anzahl", 0)
            }
        }
        return o
    }

    /** Öffnet die Anfrage von Health Connect, oder den Play Store, wenn Health Connect ein Update braucht. */
    fun verbinden() {
        when (sdk()) {
            HealthConnectClient.SDK_AVAILABLE -> try {
                if (Build.VERSION.SDK_INT >= 34) {
                    /* Ab Android 14 sind das normale Berechtigungen des Systems. Der Vertrag von Health Connect baut dort
                       einen Intent, den nur eine ComponentActivity versteht, darum direkt beim System anfragen. */
                    activity.requestPermissions(rechte.toTypedArray(), REQ)
                } else {
                    val anfrage = PermissionController.createRequestPermissionResultContract().createIntent(activity, rechte)
                    activity.startActivityForResult(anfrage, REQ)
                }
            } catch (e: Exception) {
                /* Falls trotzdem nichts aufgeht: Health Connect selbst öffnen, dort kann man FitApp von Hand erlauben */
                fehler("Anfrage ging nicht: " + e.javaClass.simpleName + ". Öffne Health Connect und erlaube FitApp dort von Hand.")
                einstellungen()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> oeffnen(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
            )
            else -> pruefen()
        }
    }

    /** Nach der Anfrage: Status neu melden, die Seite lädt dann selbst. */
    fun nachAnfrage() {
        pruefen()
    }

    /** Health Connect Einstellungen, um den Zugriff zu ändern. Fällt auf die App Infos zurück. */
    fun einstellungen() {
        val aktion = if (Build.VERSION.SDK_INT >= 34) "android.health.connect.action.HEALTH_HOME_SETTINGS" else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"
        try {
            activity.startActivity(Intent(aktion))
        } catch (e: Exception) {
            oeffnen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))
        }
    }

    private fun oeffnen(i: Intent) {
        try {
            activity.startActivity(i)
        } catch (e: Exception) {
            fehler("Konnte nicht öffnen: " + e.javaClass.simpleName)
        }
    }

    private fun fehler(text: String) {
        senden("fitHealthDaten", JSONObject().put("fehler", text).put("tage", JSONObject()))
    }

    private fun darf(erlaubt: Set<String>, art: KClass<out Record>): Boolean = HealthPermission.getReadPermission(art) in erlaubt

    /** Liest die letzten Tage (1 bis 60) und schickt pro Tag die Werte. Jede Datenart einzeln, ein Fehler stoppt die anderen nicht. */
    fun lesen(tage: Int) {
        scope.launch {
            val antwort = JSONObject()
            val probleme = mutableListOf<String>()
            val tageJson = JSONObject()
            try {
                if (sdk() != HealthConnectClient.SDK_AVAILABLE) {
                    fehler("Health Connect ist nicht verfügbar")
                    return@launch
                }
                val client = HealthConnectClient.getOrCreate(activity)
                val erlaubt = client.permissionController.getGrantedPermissions()
                val zone = ZoneId.systemDefault()
                val heute = LocalDate.now(zone)
                val n = tage.coerceIn(1, 60)
                val von = heute.minusDays((n - 1).toLong())
                val start = von.atStartOfDay()
                val ende = heute.plusDays(1).atStartOfDay()
                val bereich = TimeRangeFilter.between(start, ende)

                fun tag(d: LocalDate): JSONObject {
                    val k = d.toString()
                    var o = tageJson.optJSONObject(k)
                    if (o == null) {
                        o = JSONObject()
                        tageJson.put(k, o)
                    }
                    return o
                }

                /* Tagessummen rechnet Health Connect selbst, ohne Handy und Uhr doppelt zu zählen */
                if (darf(erlaubt, StepsRecord::class)) try {
                    client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), bereich, Period.ofDays(1))).forEach { g ->
                        g.result[StepsRecord.COUNT_TOTAL]?.let { tag(g.startTime.toLocalDate()).put("st", it) }
                    }
                } catch (e: Exception) {
                    probleme.add("Schritte " + e.javaClass.simpleName)
                }

                if (darf(erlaubt, ActiveCaloriesBurnedRecord::class)) try {
                    client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), bereich, Period.ofDays(1))).forEach { g ->
                        g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { tag(g.startTime.toLocalDate()).put("ak", Math.round(it.inKilocalories)) }
                    }
                } catch (e: Exception) {
                    probleme.add("Kalorien " + e.javaClass.simpleName)
                }

                if (darf(erlaubt, HeartRateRecord::class)) try {
                    client.aggregateGroupByPeriod(
                        AggregateGroupByPeriodRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX), bereich, Period.ofDays(1))
                    ).forEach { g ->
                        val o = tag(g.startTime.toLocalDate())
                        g.result[HeartRateRecord.BPM_AVG]?.let { o.put("pa", it) }
                        g.result[HeartRateRecord.BPM_MIN]?.let { o.put("pm", it) }
                        g.result[HeartRateRecord.BPM_MAX]?.let { o.put("px", it) }
                    }
                } catch (e: Exception) {
                    probleme.add("Puls " + e.javaClass.simpleName)
                }

                if (darf(erlaubt, RestingHeartRateRecord::class)) try {
                    client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(RestingHeartRateRecord.BPM_AVG), bereich, Period.ofDays(1))).forEach { g ->
                        g.result[RestingHeartRateRecord.BPM_AVG]?.let { tag(g.startTime.toLocalDate()).put("pr", it) }
                    }
                } catch (e: Exception) {
                    probleme.add("Ruhepuls " + e.javaClass.simpleName)
                }

                /* Schlaf: die Nacht gehört zum Tag, an dem du aufwachst. Die längste Phase bestimmt die Uhrzeiten,
                   ein Mittagsschlaf zählt zur Dauer dazu. Wachphasen werden abgezogen. */
                if (darf(erlaubt, SleepSessionRecord::class)) try {
                    val r = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minusHours(18), ende)))
                    val laengste = HashMap<String, Long>()
                    for (s in r.records) {
                        val aufgewacht = s.endTime.atZone(zone)
                        val d = aufgewacht.toLocalDate()
                        if (d.isBefore(von) || d.isAfter(heute)) continue
                        val o = tag(d)
                        val gesamt = Duration.between(s.startTime, s.endTime).toMinutes()
                        var wach = 0L
                        var tief = 0L
                        var rem = 0L
                        var leicht = 0L
                        for (p in s.stages) {
                            val m = Duration.between(p.startTime, p.endTime).toMinutes()
                            when (p.stage) {
                                SleepSessionRecord.STAGE_TYPE_AWAKE -> wach += m
                                SleepSessionRecord.STAGE_TYPE_DEEP -> tief += m
                                SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                                SleepSessionRecord.STAGE_TYPE_LIGHT -> leicht += m
                                else -> {}
                            }
                        }
                        o.put("sm", o.optLong("sm", 0L) + (gesamt - wach).coerceAtLeast(0L))
                        if (s.stages.isNotEmpty()) {
                            o.put("sd", o.optLong("sd", 0L) + tief)
                            o.put("sr", o.optLong("sr", 0L) + rem)
                            o.put("sl", o.optLong("sl", 0L) + leicht)
                            o.put("sw", o.optLong("sw", 0L) + wach)
                        }
                        val k = d.toString()
                        if (gesamt > (laengste[k] ?: -1L)) {
                            laengste[k] = gesamt
                            o.put("sv", s.startTime.atZone(zone).format(uhr))
                            o.put("sb", aufgewacht.format(uhr))
                        }
                    }
                } catch (e: Exception) {
                    probleme.add("Schlaf " + e.javaClass.simpleName)
                }

                /* Trainings von Handy und Uhr */
                if (darf(erlaubt, ExerciseSessionRecord::class)) try {
                    val r = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, bereich))
                    for (x in r.records) {
                        val beginn = x.startTime.atZone(zone)
                        val d = beginn.toLocalDate()
                        if (d.isBefore(von) || d.isAfter(heute)) continue
                        val titel = x.title
                        val name = if (titel != null && titel.isNotBlank()) titel else typName(x.exerciseType)
                        val o = tag(d)
                        var liste = o.optJSONArray("sp")
                        if (liste == null) {
                            liste = JSONArray()
                            o.put("sp", liste)
                        }
                        liste.put(
                            JSONObject()
                                .put("n", name)
                                .put("min", Duration.between(x.startTime, x.endTime).toMinutes())
                                .put("von", beginn.format(uhr))
                        )
                    }
                } catch (e: Exception) {
                    probleme.add("Trainings " + e.javaClass.simpleName)
                }

                antwort.put("tage", tageJson)
                if (probleme.isNotEmpty()) antwort.put("fehler", probleme.joinToString(", "))
            } catch (e: Exception) {
                antwort.put("tage", tageJson)
                antwort.put("fehler", "Lesen ging nicht: " + e.javaClass.simpleName)
            }
            senden("fitHealthDaten", antwort)
        }
    }

    private fun typName(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Gehen"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Laufen"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Velo"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Krafttraining"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Wandern"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        else -> "Training"
    }
}
