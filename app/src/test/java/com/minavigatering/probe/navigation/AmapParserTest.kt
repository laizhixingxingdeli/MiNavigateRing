package com.minavigatering.probe.navigation
import org.junit.Assert.*
import org.junit.Test

/** Manufactured notification text. Real Amap fixtures must be sampled separately. */
class AmapParserTest {
    private fun parse(text: String) = AmapParser.parse(AmapParser.PACKAGE, listOf(text))!!
    @Test fun turningDistanceAndRoad() {
        val i = parse("300米后右转，进入淮海中路")
        assertEquals("right", i.action); assertEquals(300, i.distanceM); assertEquals("淮海中路", i.roadName)
        assertEquals(1500, parse("1.5公里后左前方转弯").distanceM)
        assertEquals("slight-left", parse("1.5公里后左前方转弯").action)
    }
    @Test fun unrelatedAndUnsupportedAreNotStraight() {
        assertNull(AmapParser.parse("other.app", listOf("300米后右转")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("今天有雨，出行优惠")))
        assertEquals("unrecognized", parse("正在导航，重新规划路线").status)
        assertNull(parse("正在导航").action)
    }
    @Test fun endAndRoundabout() {
        assertEquals("ended", parse("导航已结束").status)
        assertEquals("roundabout-exit", parse("50米后驶出环岛，第3个出口").action)
        assertEquals(3, parse("50米后驶出环岛，第3个出口").exitNumber)
        assertEquals("uturn-right", parse("30米后向右掉头").action)
    }
    @Test fun doesNotGuessUturnSideOrAds() {
        assertEquals("unrecognized", parse("30米后掉头").status)
        assertNull(parse("30米后掉头").action)
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("右转领取优惠券")))
    }
    @Test fun stableTurnAndConsecutiveSameDirection() {
        val tracker = AmapTurnTracker()
        val a = tracker.actionId(parse("300米后右转，进入中山路"))
        assertEquals(a, tracker.actionId(parse("30米后右转，进入中山路")))
        assertNotEquals(a, tracker.actionId(parse("300米后右转，进入中山路")))
    }
    @Test fun consecutiveSameDirectionTurnSharesTheActionId() {
        // Manufactured notification text, not a real Amap sample: the two lines only
        // differ in distance keeping the same direction and the same road.
        // 同方向连续转弯没有通知实例 id，距离从 300 米缩到 280 米（未从≤100 米回跳）时
        // 无法与一次普通的距离更新区分；这里只断言当前信息不足、不得声称已识别新转弯。
        val tracker = AmapTurnTracker()
        val first = tracker.actionId(parse("300米后右转，进入中山路"))
        val second = tracker.actionId(parse("280米后右转，进入中山路"))
        assertEquals(
            "同方向连续转弯没有通知实例 id，距离未从≤100回跳时无法与距离更新区分，不得声称已识别新转弯",
            first, second,
        )
    }
    @Test fun removalGraceIsNotArrivalOrEnd() {
        assertEquals(5000L, AmapCloseout.REMOVAL_GRACE_MS)
        val grace = AmapCloseout.afterRemovalGrace(true)!!
        assertEquals("unrecognized", grace.status)
        assertNull(grace.action)
        assertNotEquals("ended", grace.status)
        assertNotEquals("arrive", grace.action)
        assertNull(AmapCloseout.afterRemovalGrace(false))
    }
    @Test fun disconnectAndEndCloseWithoutAction() {
        val disconnected = AmapCloseout.listenerDisconnected()
        assertEquals("unrecognized", disconnected.status)
        assertNull(disconnected.action)
        val ended = AmapCloseout.navigationEnded()
        assertEquals("ended", ended.status)
        assertNull(ended.action)
    }

    @Test fun ignoresRideHailingAndEtaArrivalNotices() {
        // Ride-hailing ongoing / focus notifications (with or without explicit "打车" wording)
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("预计 18:35 到达目的地", "剩余 5.6 公里")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("距目的地 3.2公里", "预计 12分钟到达目的地")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("行程中｜预计 18:35 到达目的地")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("高德打车", "即将到达目的地")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("即将到达目的地", "剩余 300 米")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("已到达目的地", "请携带好随身物品")))
        assertNull(AmapParser.parse(AmapParser.PACKAGE, listOf("已到达目的地"), sessionActive = false))
    }

    @Test fun acceptsGenuineNavigationArrivalAndIgnoresEtaSubtext() {
        // Active navigation with arrival instruction
        val withContext = AmapParser.parse(AmapParser.PACKAGE, listOf("20米后到达目的地", "高德导航中"))!!
        assertEquals("active", withContext.status)
        assertEquals("arrive", withContext.action)
        assertEquals(20, withContext.distanceM)

        // Bare arrival at the end of an already-active navigation session
        val inSession = AmapParser.parse(AmapParser.PACKAGE, listOf("已到达目的地"), sessionActive = true)!!
        assertEquals("active", inSession.status)
        assertEquals("arrive", inSession.action)
        assertNull(inSession.distanceM)

        // Arrival combined with end-of-navigation closes out cleanly
        val ended = AmapParser.parse(AmapParser.PACKAGE, listOf("到达目的地，本次导航结束"), sessionActive = true)!!
        assertEquals("ended", ended.status)

        // Generic ongoing notice + ETA subtext must not turn into premature "arrive"
        val genericWithEta = AmapParser.parse(
            AmapParser.PACKAGE,
            listOf("正在为您步行导航", "剩余 1.2公里 预计 18:50 到达目的地"),
        )!!
        assertEquals("unrecognized", genericWithEta.status)
        assertNull(genericWithEta.action)
        assertNull(genericWithEta.distanceM)

        // Turn instruction + remaining/ETA subtext keeps the turn distance, not remaining distance
        val turnWithEta = AmapParser.parse(
            AmapParser.PACKAGE,
            listOf("32米后左转", "剩余 500米 预计 6分钟到达目的地"),
        )!!
        assertEquals("left", turnWithEta.action)
        assertEquals(32, turnWithEta.distanceM)
    }

    @Test fun turnTrackerDoesNotRepeatArrivalOrInterleavedUnrecognized() {
        val tracker = AmapTurnTracker()
        val left30 = parse("30米后左转，进入中山路")
        val id1 = tracker.actionId(left30)
        // Interleaved unrecognized companion notice must not force a new turn ID for the same turn
        tracker.actionId(parse("正在导航"))
        assertEquals(id1, tracker.actionId(parse("20米后左转，进入中山路")))

        // Consecutive arrival updates in the same session keep one actionId (vibrate once)
        val arriveWithRoad = AmapParser.parse(
            AmapParser.PACKAGE,
            listOf("15米后到达目的地，进入中山路", "高德导航中"),
        )!!
        val arriveBare = AmapParser.parse(
            AmapParser.PACKAGE,
            listOf("已到达目的地", "高德导航中"),
        )!!
        val arriveId = tracker.actionId(arriveWithRoad)
        assertEquals(arriveId, tracker.actionId(arriveBare))

        // Session end resets the tracker so the next session gets a fresh turn ID
        tracker.actionId(parse("导航已结束"))
        assertNotEquals(arriveId, tracker.actionId(arriveBare))
    }
}
