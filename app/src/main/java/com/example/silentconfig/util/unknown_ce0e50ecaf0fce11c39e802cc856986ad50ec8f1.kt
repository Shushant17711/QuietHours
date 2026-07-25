package com.example.aquiethours.util

fun format12Hour(hour: Int, minute: Int): String {
    val isPm = hour >= 12
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val amPm = if (isPm) "PM" else "AM"
    return String.format("%02d:%02d %s", displayHour, minute, amPm)
}
