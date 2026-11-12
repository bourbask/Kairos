package app.kairos.mobile.ui.politique

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kairos.mobile.data.PolIdentity
import app.kairos.mobile.ui.mascot.animate
import app.kairos.mobile.ui.theme.KairosColors
import app.kairos.mobile.ui.theme.SceneColors
import app.kairos.mobile.ui.theme.contrast
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** Côté de la grille d'un avatar. */
const val AVATAR_SIZE = 16

/**
 * Rôles d'un avatar : corps, ombre, encre, accent du thème, `C` = couleur de l'identité, puis
 * cheveux à teinte fixe, la même dans tous les thèmes : `g` blancs ou gris, `b` bruns, `y` blonds.
 */
const val AVATAR_ROLES = "FhDACgby"

/** Teintes fixes des cheveux, lisibles sur le corps et sur le fond du bouton de chaque thème. */
val HAIR_COLORS = mapOf('g' to Color(0xFFB4B8BB), 'b' to Color(0xFF5C3A21), 'y' to Color(0xFFD9B44A))

/** Images empaquetées (x | y << 8 | rôle << 16, cases vides omises) et durée d'une image. */
class Avatar(val frames: List<IntArray>, val ms: Long)

/**
 * Avatar publié par le module, validé : 1 à 4 images de 16 lignes de 16 caractères pris dans
 * `.` et [AVATAR_ROLES]. Toute autre forme : null (pastille de couleur à la place).
 */
fun avatarOf(e: JsonElement?): Avatar? {
    val o = e as? JsonObject ?: return null
    val images = o["images"] as? JsonArray ?: return null
    if (images.size !in 1..4) return null
    val frames = images.map { img ->
        val rows = (img as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: return null
        if (rows.size != AVATAR_SIZE || rows.any { it.length != AVATAR_SIZE }) return null
        val out = ArrayList<Int>()
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, c ->
                if (c == '.') return@forEachIndexed
                val role = AVATAR_ROLES.indexOf(c)
                if (role < 0) return null
                out += x or (y shl 8) or (role shl 16)
            }
        }
        out.toIntArray()
    }
    val ms = (o["ms"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 400L
    return Avatar(frames, ms.coerceIn(80L, 5_000L))
}

/** Teinte d'une case : rôles des personnages du thème, `C` = [identity], cheveux = [HAIR_COLORS]. */
fun avatarColor(packed: Int, colors: SceneColors, identity: Color): Color {
    val role = AVATAR_ROLES[packed shr 16]
    return if (role == 'C') identity else HAIR_COLORS[role] ?: colors[role]
}

/** Couleur `#RRGGBB` de l'identité ; illisible : null. */
fun identityColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * Vignette d'une identité : son personnage s'il est publié (images en boucle, figé quand les
 * animations du système sont coupées), sinon une pastille de sa couleur et son initiale.
 * Décorative : le nom est toujours écrit à côté.
 */
@Composable
fun IdentityBadge(identity: PolIdentity, size: Dp, modifier: Modifier = Modifier) {
    val avatar = remember(identity.avatar) { avatarOf(identity.avatar) }
    val tint = identityColor(identity.couleur) ?: MaterialTheme.colorScheme.primary
    if (avatar == null) {
        // Liseré : une couleur claire (blanc, jaune) reste visible sur une surface claire.
        val ink = KairosColors.current.scene['D']
        Box(
            modifier.size(size).background(tint, CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                identity.nom.take(1).uppercase(),
                color = if (contrast(Color.White, tint) >= contrast(ink, tint)) Color.White else ink,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }
    // Visages couleur du corps, sans contour : posés sur la pastille du bouton Kairos, comme le personnage.
    val colors = KairosColors.current.scene
    var frame by remember(avatar) { mutableIntStateOf(0) }
    LaunchedEffect(avatar) {
        if (avatar.frames.size < 2) return@LaunchedEffect
        animate(still = { frame = 0 }) { elapsed, _ -> frame = ((elapsed / avatar.ms) % avatar.frames.size).toInt() }
    }
    Box(modifier.size(size).background(KairosColors.current.btn, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(size * 0.72f)) {
        val cell = this.size.minDimension / AVATAR_SIZE
        // Bords arrondis au pixel : cases jointives, sans liseré.
        fun edge(i: Int) = (i * cell).roundToInt().toFloat()
        for (v in avatar.frames[frame.coerceIn(0, avatar.frames.size - 1)]) {
            val x = v and 0xFF
            val y = (v shr 8) and 0xFF
            drawRect(avatarColor(v, colors, tint), Offset(edge(x), edge(y)), Size(edge(x + 1) - edge(x), edge(y + 1) - edge(y)))
        }
    }
    }
}
