package dev.ravindran.ythub.mobile

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/**
 * BitChord TabLibrary glyph, used with attribution under GPL-3.0-or-later.
 * Source: TheAayan0007/bitchord, sharedUi/.../BitChordIcons.kt, commit
 * acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc. The glyph is tinted by Compose.
 */
internal val BitChordLibraryTabPath: Path by lazy {
    val path = "M154 27.24Q156 0.24 185 0.24H322Q352 0.24 353 27.24Z" +
        "M116 87.24Q121 56.24 150 56.24H356Q385 56.24 390 87.24Z" +
        "M133 500.24Q66 500.24 66 434.24V187.24Q66 121.24 133 121.24H378Q446 121.24 446 187.24V434.24Q446 500.24 385 500.24Z"
    PathParser().parsePathString(path).toPath()
}
