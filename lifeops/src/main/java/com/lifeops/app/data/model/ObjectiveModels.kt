package com.lifeops.app.data.model

enum class ObjectiveStatus(val value: String, val label: String) {
    ACTIVE("active", "Active"),
    SUCCEEDED("succeeded", "Succeeded"),
    UNSUCCESSFUL("unsuccessful", "Unsuccessful");

    companion object { fun from(value: String?) = entries.firstOrNull { it.value == value } ?: ACTIVE }
}

data class Objective(
    val id: String,
    val title: String,
    val aspectId: String?,
    val dueDate: String,
    val successCriteria: String,
    val status: ObjectiveStatus,
    val createdAt: String,
    val updatedAt: String,
    val closedAt: String? = null
)

data class ObjectiveStep(
    val id: String,
    val objectiveId: String,
    val position: Int,
    val title: String,
    val opensOn: String? = null,
    val afterPrevious: Boolean = false,
    val dueDate: String? = null,
    val completedAt: String? = null
) {
    val isDone: Boolean get() = completedAt != null
}

/** An objective with its steps in position order. */
data class ObjectiveWithSteps(val objective: Objective, val steps: List<ObjectiveStep>)
