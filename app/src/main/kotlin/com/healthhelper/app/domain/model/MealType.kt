package com.healthhelper.app.domain.model

enum class MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    UNKNOWN;

    companion object {
        fun fromFoodScannerId(id: Int): MealType = when (id) {
            1 -> BREAKFAST
            3 -> LUNCH
            5 -> DINNER
            2, 4, 7 -> SNACK
            else -> UNKNOWN
        }
    }
}
