package com.icqnew.app.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's own icon set (drawn for ICQ New, 24x24, rounded filled style). */
object IcqIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
            .build()

    val Chats: ImageVector by lazy {
        icon("chats", "M12 3C6.5 3 2 6.8 2 11.4c0 2.6 1.4 4.9 3.7 6.4L5 21.2c-.1.5.4.9.9.6l3.6-2.1c.8.2 1.6.2 2.5.2 5.5 0 10-3.8 10-8.5S17.5 3 12 3z M7.6 12.9a1.4 1.4 0 1 1 0-2.8 1.4 1.4 0 0 1 0 2.8z M12 12.9a1.4 1.4 0 1 1 0-2.8 1.4 1.4 0 0 1 0 2.8z M16.4 12.9a1.4 1.4 0 1 1 0-2.8 1.4 1.4 0 0 1 0 2.8z")
    }

    val Channels: ImageVector by lazy {
        icon("channels", "M18.5 4.2c-.3-.2-.7-.2-1 0L11 8H6a3 3 0 0 0-3 3v1a3 3 0 0 0 3 3h.6l1.2 4.3c.2.7.8 1.2 1.6 1.2h.6c1 0 1.7-.9 1.5-1.9L10.6 15h.4l6.5 3.8c.3.2.7.2 1 0 .3-.2.5-.5.5-.9V5.1c0-.4-.2-.7-.5-.9z M21 9.2c-.6 0-1 .4-1 1v2.6c0 .6.4 1 1 1s1-.4 1-1v-2.6c0-.6-.4-1-1-1z")
    }

    val Calls: ImageVector by lazy {
        icon("calls", "M7.2 3.1 9 5.6c.5.7.4 1.6-.2 2.2L7.6 9c1.3 2.6 3.4 4.7 6 6l1.2-1.2c.6-.6 1.5-.7 2.2-.2l2.5 1.8c.8.6 1 1.7.4 2.5l-1.1 1.6c-.7 1-2 1.5-3.2 1.1C10 19.3 4.7 14 3.4 8.4c-.3-1.2.1-2.5 1.1-3.2l1.6-1.1c.4-.3.8-.4 1.1-.4z")
    }

    val Contacts: ImageVector by lazy {
        icon("contacts", "M12 12.2a4.6 4.6 0 1 0 0-9.2 4.6 4.6 0 0 0 0 9.2z M12 14c-4.4 0-8 2.4-8 5.4 0 .9.7 1.6 1.6 1.6h12.8c.9 0 1.6-.7 1.6-1.6 0-3-3.6-5.4-8-5.4z")
    }

    val Settings: ImageVector by lazy {
        icon("settings", "M4 6h7.2a2.8 2.8 0 0 1 5.6 0H20a1 1 0 1 1 0 2h-3.2a2.8 2.8 0 0 1-5.6 0H4a1 1 0 1 1 0-2z M4 16h1.2a2.8 2.8 0 0 1 5.6 0H20a1 1 0 1 1 0 2h-9.2a2.8 2.8 0 0 1-5.6 0H4a1 1 0 1 1 0-2z")
    }

    val Video: ImageVector by lazy {
        icon("video", "M5 6h9a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z M18 10.2l3-2c.7-.4 1.5.1 1.5.9v5.8c0 .8-.8 1.3-1.5.9l-3-2z")
    }

    /** Friendly robot head with an antenna (bots tab). */
    val Bot: ImageVector by lazy {
        icon(
            "bot",
            "M12 1.6a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2z M11.3 4.6h1.4V7h-1.4z " +
                "M7.5 7h9A3.5 3.5 0 0 1 20 10.5v5a3.5 3.5 0 0 1-3.5 3.5h-9A3.5 3.5 0 0 1 4 15.5v-5A3.5 3.5 0 0 1 7.5 7z " +
                "M9 10.9a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z M15 10.9a1.6 1.6 0 1 0 0 3.2 1.6 1.6 0 0 0 0-3.2z " +
                "M10 15.4h4a.6.6 0 0 1 0 1.2h-4a.6.6 0 0 1 0-1.2z " +
                "M2.2 11h1.2v4H2.2a.6.6 0 0 1-.6-.6v-2.8a.6.6 0 0 1 .6-.6z M20.6 11h1.2a.6.6 0 0 1 .6.6v2.8a.6.6 0 0 1-.6.6h-1.2z",
        )
    }
}
