package com.securevault.pro.presentation.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securevault.pro.presentation.theme.VaultPaletteController

/**
 * Draws one glyph from the icon board. The plate colour and the white glyph are baked
 * into the generated drawable, so nothing is tinted here; the same module is the same
 * shape in every theme and only its plate recolours with the palette.
 */
@Composable
fun BrandModuleIcon(
    module: VaultModule,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    themeId: String? = null,
    contentDescription: String? = module.label
) {
    val activeId = themeId ?: VaultPaletteController.current.id
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Crossfade(targetState = activeId to module, label = "brandIconSet") { (id, glyph) ->
            Image(
                painter = painterResource(BrandIconCatalog.icon(id, glyph)),
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Fit
            )
        }
    }
}
