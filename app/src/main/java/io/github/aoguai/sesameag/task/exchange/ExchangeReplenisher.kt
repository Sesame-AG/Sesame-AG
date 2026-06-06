package io.github.aoguai.sesameag.task.exchange

import io.github.aoguai.sesameag.model.Model
import io.github.aoguai.sesameag.task.antFarm.AntFarm
import io.github.aoguai.sesameag.task.antForest.AntForest
import io.github.aoguai.sesameag.task.antMember.AntMember
import io.github.aoguai.sesameag.task.antSports.AntSports
import io.github.aoguai.sesameag.util.Log

object ExchangeReplenisher {
    private const val TAG = "ExchangeReplenisher"

    fun replenish(
        need: ExchangeEffectNeed,
        reason: String,
        maxCount: Int = 1,
        afterExchangeRefresh: () -> Unit = {}
    ): ExchangeReplenishResult {
        val safeMaxCount = maxCount.coerceAtLeast(1)
        var sawNotSelected = false
        var sawBusinessLimit = false
        var sawRetryLater = false
        var sawNotAvailable = false
        for (provider in providersFor(need)) {
            val result = runCatching { provider(need, reason, safeMaxCount) }
                .onFailure { Log.printStackTrace(TAG, "replenish[$need] provider err:", it) }
                .getOrDefault(ExchangeReplenishResult.RETRY_LATER)
            when (result) {
                ExchangeReplenishResult.EXCHANGED -> {
                    runCatching { afterExchangeRefresh() }
                        .onFailure { Log.printStackTrace(TAG, "replenish[$need] refresh err:", it) }
                    return ExchangeReplenishResult.EXCHANGED
                }
                ExchangeReplenishResult.NOT_SELECTED -> sawNotSelected = true
                ExchangeReplenishResult.NOT_AVAILABLE -> sawNotAvailable = true
                ExchangeReplenishResult.BUSINESS_LIMIT -> sawBusinessLimit = true
                ExchangeReplenishResult.RETRY_LATER -> sawRetryLater = true
                ExchangeReplenishResult.UNSUPPORTED -> Unit
            }
        }
        return when {
            sawRetryLater -> ExchangeReplenishResult.RETRY_LATER
            sawBusinessLimit -> ExchangeReplenishResult.BUSINESS_LIMIT
            sawNotAvailable -> ExchangeReplenishResult.NOT_AVAILABLE
            sawNotSelected -> ExchangeReplenishResult.NOT_SELECTED
            else -> ExchangeReplenishResult.UNSUPPORTED
        }
    }

    private fun providersFor(
        need: ExchangeEffectNeed
    ): List<(ExchangeEffectNeed, String, Int) -> ExchangeReplenishResult> {
        return when (need) {
            ExchangeEffectNeed.FOREST_DOUBLE_CLICK,
            ExchangeEffectNeed.FOREST_PATROL_CHANCE -> listOf(
                { n, r, c -> forestProvider(n, r, c) },
                { n, r, c -> sportsProvider(n, r, c) },
                { n, r, c -> memberProvider(n, r, c) }
            )
            ExchangeEffectNeed.FOREST_SHIELD,
            ExchangeEffectNeed.FOREST_STEALTH,
            ExchangeEffectNeed.FOREST_BUBBLE_BOOST,
            ExchangeEffectNeed.FOREST_ENERGY_RAIN,
            ExchangeEffectNeed.FOREST_ENERGY_BOMB,
            ExchangeEffectNeed.FOREST_ROB_MULTIPLIER,
            ExchangeEffectNeed.OCEAN_UNIVERSAL_PIECE,
            ExchangeEffectNeed.OCEAN_RANDOM_PIECE,
            ExchangeEffectNeed.DODO_HISTORY_CARD,
            ExchangeEffectNeed.DODO_FRIEND_CARD -> listOf(
                { n, r, c -> forestProvider(n, r, c) },
                { n, r, c -> memberProvider(n, r, c) }
            )
            ExchangeEffectNeed.FARM_FEED,
            ExchangeEffectNeed.FARM_ACCELERATE_TOOL,
            ExchangeEffectNeed.FARM_BIG_EATER_TOOL,
            ExchangeEffectNeed.FARM_FENCE_TOOL,
            ExchangeEffectNeed.FARM_NEW_EGG_TOOL -> listOf(
                { n, r, c -> farmProvider(n, r, c) },
                { n, r, c -> memberProvider(n, r, c) }
            )
            ExchangeEffectNeed.ORCHARD_FERTILIZER -> listOf(
                { n, r, c -> memberProvider(n, r, c) }
            )
            ExchangeEffectNeed.MEMBER_GOLD_TICKET -> listOf(
                { n, r, c -> memberProvider(n, r, c) }
            )
        }
    }

    private fun forestProvider(need: ExchangeEffectNeed, reason: String, maxCount: Int): ExchangeReplenishResult {
        return Model.getModel(AntForest::class.java)
            ?.replenishExchangeByNeed(need, reason, maxCount)
            ?: ExchangeReplenishResult.UNSUPPORTED
    }

    private fun sportsProvider(need: ExchangeEffectNeed, reason: String, maxCount: Int): ExchangeReplenishResult {
        return Model.getModel(AntSports::class.java)
            ?.replenishExchangeByNeed(need, reason, maxCount)
            ?: ExchangeReplenishResult.UNSUPPORTED
    }

    private fun farmProvider(need: ExchangeEffectNeed, reason: String, maxCount: Int): ExchangeReplenishResult {
        return Model.getModel(AntFarm::class.java)
            ?.replenishExchangeByNeed(need, reason, maxCount)
            ?: ExchangeReplenishResult.UNSUPPORTED
    }

    private fun memberProvider(need: ExchangeEffectNeed, reason: String, maxCount: Int): ExchangeReplenishResult {
        return Model.getModel(AntMember::class.java)
            ?.replenishExchangeByNeed(need, reason, maxCount)
            ?: ExchangeReplenishResult.UNSUPPORTED
    }
}
