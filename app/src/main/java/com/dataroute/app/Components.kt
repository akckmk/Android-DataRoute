package com.dataroute.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The rounded white card everything sits in. */
@Composable
fun Bubble(
    modifier: Modifier = Modifier,
    color: Color = DR.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = color, shadowElevation = 1.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A coloured circle holding an icon. */
@Composable
fun IconBlob(@DrawableRes icon: Int, tint: Color, bg: Color, size: Dp = 44.dp, description: String? = null) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), description, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** A small rounded label, e.g. "Internet working". */
@Composable
fun Pill(text: String, fg: Color, bg: Color, @DrawableRes icon: Int? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = DR.Ink)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = DR.Sub)
    }
}

@Composable
fun BubbleTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, color = DR.Ink)

/** Visual identity per kind of home service. */
data class KindLook(@DrawableRes val icon: Int, val tint: Color, val bg: Color)

fun ServiceKind.look(): KindLook = when (this) {
    ServiceKind.PROTECT -> KindLook(R.drawable.ic_camera, DR.SkyInk, DR.SkySoft)
    ServiceKind.HOME_ASSISTANT -> KindLook(R.drawable.ic_home, DR.MintInk, DR.MintSoft)
    ServiceKind.PLEX -> KindLook(R.drawable.ic_play, DR.SunnyInk, DR.SunnySoft)
    ServiceKind.JELLYFIN -> KindLook(R.drawable.ic_movie, DR.GrapeInk, DR.GrapeSoft)
    ServiceKind.NAS -> KindLook(R.drawable.ic_nas, DR.TangerineInk, DR.TangerineSoft)
    ServiceKind.OTHER -> KindLook(R.drawable.ic_globe, Color(0xFFB0256F), DR.PinkSoft)
}
