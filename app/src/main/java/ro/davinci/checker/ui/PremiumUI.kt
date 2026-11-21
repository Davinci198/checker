package ro.davinci.checker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Elemente vizuale din conceptul „checker-ui-premium.html”: fundal cu trei
 * glow-uri radiale, carduri cu strat de sticlă + bordură fină și text cu
 * gradient. Ținute aici ca să nu îngroașe CheckerScreen.kt.
 */
object Premium {
    val Green = Color(0xFF4ADE80)
    val Blue = Color(0xFF60A5FA)
    val Violet = Color(0xFFC084FC)
    val Amber = Color(0xFFFBBF24)
    val Muted = Color(0xFF94A3B8)

    /** Bordură albă cu opacitate mică, ca în CSS-ul premium. */
    val Border = Color(0x1FFFFFFF)

    /** Colțuri rotunde generoase — senzația de card, nu de listă înghesuită. */
    val Shape = RoundedCornerShape(22.dp)

    /** Sticla cardului: alb 8% sus → alb 2% jos (rgba din HTML). */
    val Surface = Brush.verticalGradient(
        listOf(Color(0x14FFFFFF), Color(0x05FFFFFF)),
    )

    /** Gradientul pentru cifrele mari: verde → albastru → violet. */
    val Gradient = listOf(Green, Blue, Violet)
}

/** Fundalul cu glow-uri, adaptat la dimensiunea ecranului (DrawScope.size). */
fun Modifier.premiumGlow(): Modifier = drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x4722D38E), Color(0x0022D38E)),
            center = Offset(0f, 0f),
            radius = size.width * 0.95f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x4D60A5FA), Color(0x0060A5FA)),
            center = Offset(size.width, 0f),
            radius = size.width * 0.9f,
        ),
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x3AA855F7), Color(0x00A855F7)),
            center = Offset(size.width * 0.5f, size.height),
            radius = size.width * 0.95f,
        ),
    )
}

/** Card premium: umbră + sticlă + bordură; culoarea transparentă se pune pe Card. */
fun Modifier.premiumCard(): Modifier = this
    .shadow(12.dp, Premium.Shape)
    .background(Premium.Surface, Premium.Shape)
    .border(1.dp, Premium.Border, Premium.Shape)

/** Text cu gradient, folosit pentru cifrele mari (CPU, baterie, antet). */
@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    colors: List<Color> = Premium.Gradient,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(brush = Brush.horizontalGradient(colors)),
    )
}
