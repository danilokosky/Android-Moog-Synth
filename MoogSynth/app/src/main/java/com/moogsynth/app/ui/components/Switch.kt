package com.moogsynth.app.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun SynthSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFFEF4444),
            checkedTrackColor = Color(0xFF991B1B),
            uncheckedThumbColor = Color.LightGray,
            uncheckedTrackColor = Color.DarkGray
        )
    )
}
