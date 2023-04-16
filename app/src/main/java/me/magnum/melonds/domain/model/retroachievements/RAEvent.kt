package me.magnum.melonds.domain.model.retroachievements

sealed class RAEvent {
    data class OnAchievementPrimed(val achievementId: Long) : RAEvent()
    data class OnAchievementUnPrimed(val achievementId: Long) : RAEvent()
    data class OnAchievementTriggered(val achievementId: Long) : RAEvent()
}