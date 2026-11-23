package com.jagabocil.app

internal sealed interface ReadingStep {
    data object Read : ReadingStep
    data object Recording : ReadingStep
    data object Preview : ReadingStep
    data class Waiting(val recitation: Recitation) : ReadingStep
    data class Approved(val recitation: Recitation) : ReadingStep

    val showsVerses get() = this == Read || this == Recording
}

internal fun readingStep(recording: Boolean, hasDraft: Boolean, history: List<Recitation>, now: Long): ReadingStep {
    if (recording) return ReadingStep.Recording
    if (hasDraft) return ReadingStep.Preview
    history.firstOrNull { it.canUse(now) }?.let { return ReadingStep.Approved(it) }
    history.firstOrNull { it.status == "pending" }?.let { return ReadingStep.Waiting(it) }
    return ReadingStep.Read
}
