package com.minavigatering.probe.navigation

/** Text-only parser. Never infers an arrow from an unknown notification or icon. */
data class AmapInstruction(val status: String, val action: String? = null,
    val distanceM: Int? = null, val roadName: String? = null, val exitNumber: Int? = null)

/**
 * Closeout states are built here so "listener gone" and "route ended" can never be
 * confused with a real turn: both carry no action, and the removal grace is neither an
 * arrival nor an end until no tracked Amap notification is left.
 */
object AmapCloseout {
    const val REMOVAL_GRACE_MS = 5_000L
    fun listenerDisconnected(): AmapInstruction = AmapInstruction(status = "unrecognized")
    fun navigationEnded(): AmapInstruction = AmapInstruction(status = "ended")
    fun afterRemovalGrace(noTrackedAmapNotifications: Boolean): AmapInstruction? =
        if (noTrackedAmapNotifications) AmapInstruction(status = "unrecognized") else null
}

object AmapParser {
    const val PACKAGE = "com.autonavi.minimap"
    private val turnDirections = listOf(
        "向左掉头" to "uturn-left", "左侧掉头" to "uturn-left", "向右掉头" to "uturn-right",
        "右侧掉头" to "uturn-right",
        "驶出环岛" to "roundabout-exit", "离开环岛" to "roundabout-exit",
        "进入环岛" to "roundabout-enter", "左后方" to "sharp-left", "右后方" to "sharp-right",
        "左前方" to "slight-left", "右前方" to "slight-right",
        "靠左" to "keep-left", "靠右" to "keep-right", "左转" to "left", "右转" to "right",
        "直行" to "straight", "继续向前" to "straight")
    // Ride-hailing / taxi / order cards share com.autonavi.minimap but are never walk/cycle navigation.
    private val nonNavigationKeywords = listOf(
        "高德打车", "打车", "快车", "专车", "顺风车", "出租车", "网约车", "代驾", "拼车",
        "接驾", "候车", "叫车", "派单", "司机", "车牌",
        "行程中", "行程结束", "行程已结束", "待支付", "车费", "免密支付",
        "准备下车", "随身物品", "系好安全带", "后方来车")
    private val endedPhrases = listOf("导航已结束", "结束导航", "已退出导航", "导航结束")
    private val navigationContexts = listOf(
        "导航中", "正在导航", "步行导航", "骑行导航", "路线重新规划", "重新规划路线")
    private val clauseSplit = Regex("[\\n，,。；;|｜]+")
    private val etaArrivalPrefix = Regex(
        "(?:预计|约|大概|\\d{1,2}:\\d{2}|\\d+\\s*(?:小时|分钟|分)|剩余|距|距离|还有|全程|\\d+(?:\\.\\d+)?\\s*(?:公里|千米))"
    )
    private val arrivalMetersPrefix = Regex("(\\d+(?:\\.\\d+)?)\\s*米")
    private val distance = Regex("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米)(?:后|处|\\s|，|,|$)")
    private val totalDistancePrefix = Regex("(?:剩余|全程|距目的地|距离目的地|距终点|距离终点|距您|距离您)\\s*$")
    private val road = Regex("(?:进入|驶入|转入|沿着|沿)\\s*([^，。；\\n]{1,40}?(?:大道|公路|街|路|巷|桥))")
    private val exit = Regex("第\\s*(\\d+)\\s*(?:个)?出口")

    fun parse(packageName: String, fields: List<String>, sessionActive: Boolean = false): AmapInstruction? {
        if (packageName != PACKAGE) return null
        val text = fields.filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (text.isBlank()) return null
        if (nonNavigationKeywords.any { it in text }) return null
        if (endedPhrases.any { it in text }) return AmapInstruction("ended")

        val navigationContext = navigationContexts.any { it in text }
        val turnFound = turnDirections.firstOrNull { it.first in text }
        val arrivalFound = if (turnFound == null && hasArrivalInstruction(text)) {
            "到达目的地" to "arrive"
        } else {
            null
        }
        val found = turnFound ?: arrivalFound
        val match = distance.findAll(text).firstOrNull { m ->
            !totalDistancePrefix.containsMatchIn(text.substring(0, m.range.first))
        }

        // A standalone arrival without navigation context or an active session (e.g. ride-hailing
        // trip cards or post-trip arrival reminders) must never start a phantom session.
        if (found?.second == "arrive" && !navigationContext && !sessionActive) return null

        // Ads, weather and generic Amap notices are not navigation.
        if (!navigationContext && !(match != null && (found != null || "掉头" in text)) && found?.second != "arrive") {
            return null
        }

        val metres = match?.let {
            val value = it.groupValues[1].toDoubleOrNull() ?: return@let null
            val m = value * if (it.groupValues[2] == "米") 1 else 1000
            if (m.isFinite() && m >= 0 && m <= 100000) kotlin.math.round(m).toInt() else null
        }
        return AmapInstruction(if (found == null) "unrecognized" else "active", found?.second,
            metres, road.find(text)?.groupValues?.get(1)?.trim(), exit.find(text)?.groupValues?.get(1)?.toIntOrNull())
    }

    /**
     * Matches genuine arrival prompts ("已到达目的地", "20米后到达目的地") while rejecting
     * ETA / remaining-trip text ("预计 18:35 到达目的地", "预计12分钟到达目的地", "剩余3.5公里...").
     */
    private fun hasArrivalInstruction(text: String): Boolean =
        clauseSplit.split(text).any { clause ->
            if ("到达目的地" !in clause) return@any false
            val prefix = clause.substringBefore("到达目的地")
            if (etaArrivalPrefix.containsMatchIn(prefix)) return@any false
            val meters = arrivalMetersPrefix.find(prefix)?.groupValues?.get(1)?.toDoubleOrNull()
            meters == null || meters <= 300.0
        }
}

/**
 * Distance updates preserve a turn ID; a new turn or route session creates one.
 *
 * [tag] only widens the id shape to `amap-turn-<tag>-<n>` so two listener instances of the
 * same process cannot share a turn number; an empty tag keeps the historical
 * `amap-turn-<n>` form. It never changes which notification starts a new turn.
 */
class AmapTurnTracker(private val tag: String = "") {
    private var lastActive: AmapInstruction? = null
    private var turn = 0L

    fun reset() {
        lastActive = null
    }

    fun actionId(next: AmapInstruction): String {
        if (next.status == "ended") {
            lastActive = null
            return formatId()
        }
        if (next.status != "active") {
            return formatId()
        }
        val previous = lastActive
        val sameSessionArrival = previous?.action == "arrive" && next.action == "arrive"
        if (!sameSessionArrival && (
                previous == null ||
                    next.action != previous.action ||
                    next.roadName != previous.roadName ||
                    next.exitNumber != previous.exitNumber ||
                    (previous.distanceM != null && next.distanceM != null &&
                        previous.distanceM <= 100 && next.distanceM > previous.distanceM + 100)
            )
        ) {
            turn++
        }
        lastActive = next
        return formatId()
    }

    private fun formatId(): String = if (tag.isEmpty()) "amap-turn-$turn" else "amap-turn-$tag-$turn"
}
