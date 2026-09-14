package com.lifeops.app.data.model



/**
 * Longer-running work: operations, the runbooks and subtasks that make one up, the templates
 * a week is laid out from, and the future operations waiting for a week to put them in.
 */

enum class OperationStatus(val value: String) {
    ACTIVE("active"),
    COMPLETED("completed");
    companion object { fun from(value: String) = entries.firstOrNull { it.value == value } ?: ACTIVE }
}

data class Operation(
    val id: String,
    val title: String,
    val aspectId: String? = null,
    val categoryId: String? = null,
    val status: OperationStatus = OperationStatus.ACTIVE,
    val description: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
    val sourceFutureOperationId: String? = null
)

data class OperationStats(
    val operation: Operation,
    val taskCount: Int,
    val completedCount: Int,
    val totalTimeMinutes: Int
)

data class Runbook(
    val id: String,
    val name: String,
    val createdAt: String
)

data class RunbookStep(
    val id: String,
    val runbookId: String,
    val label: String,
    val stepOrder: Int
)

data class RunbookWithSteps(
    val runbook: Runbook,
    val steps: List<RunbookStep>
)

data class Subtask(
    val id: String,
    val taskId: String,
    val runbookId: String?,
    val label: String,
    val stepOrder: Int,
    val isChecked: Boolean = false
)

data class Template(
    val id: String,
    val name: String,
    val createdAt: String
)

data class TemplateTask(
    val id: String,
    val templateId: String,
    val title: String,
    val aspectName: String? = null,
    val categoryName: String? = null,
    val priority: String = "medium",
    val estimatedMinutes: Int? = null,
    val runbookId: String? = null,
    val taskOrder: Int = 0
)

data class TemplateWithTasks(
    val template: Template,
    val tasks: List<TemplateTask>
)

enum class FutureOperationStatus(val value: String) {
    ACTIVE("active"),
    ARCHIVED("archived");
    companion object { fun from(value: String) = entries.firstOrNull { it.value == value } ?: ACTIVE }
}

data class FutureOperation(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val status: FutureOperationStatus = FutureOperationStatus.ACTIVE
)

data class FutureOperationNote(
    val id: String,
    val operationId: String,
    val content: String,
    val createdAt: String
)
