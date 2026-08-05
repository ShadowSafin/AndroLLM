package io.androllm.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.androllm.core.ui.R

/**
 * The AndroLLM logo mark. Rendered from the shared [io.androllm.core.ui.R.drawable.logo]
 * asset so the same mark appears everywhere — splash, auth, settings, drawer and home.
 */
@Composable
fun CloudBugdroidLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    showMoon: Boolean = true
) {
    Box(modifier = modifier.size(size)) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = "AndroLLM logo",
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    }
}
