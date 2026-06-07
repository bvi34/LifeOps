package com.lifeops.app.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)

val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val SurfaceVariantDark = Color(0xFF2A2A2A)

val PriorityLow = Color(0xFF9E9E9E)
val PriorityMedium = Color(0xFF2196F3)
val PriorityHigh = Color(0xFFFF9800)
val PriorityCritical = Color(0xFFF44336)

val CompletedGreen = Color(0xFF4CAF50)
val ExpiredRed = Color(0xFFF44336)
val SkippedGray = Color(0xFF757575)

fun priorityColor(priority: String) = when (priority) {
    "low" -> PriorityLow
    "medium" -> PriorityMedium
    "high" -> PriorityHigh
    "critical" -> PriorityCritical
    else -> PriorityMedium
}

fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        Color(0xFF6200EE)
    }
}
