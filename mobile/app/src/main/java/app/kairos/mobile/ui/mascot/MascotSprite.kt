package app.kairos.mobile.ui.mascot

enum class MascotState { Repos, Ecoute, Reflechit, Agit, Attend }

enum class PixelRole { Corps, Reflet, Meche, Oeil, Pied, Aile, Onde, Bulle }

data class Pixel(val x: Int, val y: Int, val role: PixelRole)

/**
 * Le personnage sur une grille 16×16 : corps carré, deux yeux, mèche en avant,
 * petites ailes aux pieds. L'état se lit dans l'expression et le mouvement ;
 * `frame` avance de façon continue, chaque état y découpe son propre cycle.
 * Les pixels sont rendus dans l'ordre de la liste (les yeux recouvrent le corps).
 */
object MascotSprite {
    const val SIZE = 16

    /** Image du clignement dans le cycle de repos (1 sur [BLINK_EVERY]). */
    private const val BLINK_EVERY = 20

    fun pixels(state: MascotState, frame: Int): List<Pixel> {
        val odd = frame % 2 == 1
        val out = mutableListOf<Pixel>()
        fun rect(x0: Int, y0: Int, w: Int, h: Int, role: PixelRole) {
            for (y in y0 until y0 + h) for (x in x0 until x0 + w) out += Pixel(x, y, role)
        }

        fun eyes(y: Int, h: Int) {
            rect(5, y, 2, h, PixelRole.Oeil)
            rect(9, y, 2, h, PixelRole.Oeil)
        }

        // Corps et reflet sur le haut du crâne.
        rect(3, 5, 10, 1, PixelRole.Reflet)
        rect(3, 6, 10, 7, PixelRole.Corps)

        // Mèche en avant ; en attente, sa pointe oscille.
        val sway = state == MascotState.Attend && odd
        rect(6, 3, 3, 2, PixelRole.Meche)
        rect(if (sway) 9 else 8, 2, 3, 1, PixelRole.Meche)
        rect(if (sway) 11 else 10, 1, 2, 1, PixelRole.Meche)

        // Yeux : l'expression porte l'état.
        when (state) {
            MascotState.Repos ->
                if (frame % BLINK_EVERY == BLINK_EVERY - 1) eyes(y = 9, h = 1) else eyes(y = 8, h = 2)
            MascotState.Ecoute -> eyes(y = 7, h = 3)
            MascotState.Reflechit -> eyes(y = 8, h = 1)
            MascotState.Agit -> eyes(y = 8, h = 2)
            MascotState.Attend -> eyes(y = 9, h = 2)
        }

        // Pieds et petites ailes ; en action, les ailes battent.
        rect(4, 13, 2, 1, PixelRole.Pied)
        rect(10, 13, 2, 1, PixelRole.Pied)
        val wingY = if (state == MascotState.Agit && odd) 11 else 12
        rect(2, wingY, 2, 1, PixelRole.Aile)
        rect(12, wingY, 2, 1, PixelRole.Aile)

        // Accessoires d'état : ondes à l'écoute, bulles à la réflexion.
        if (state == MascotState.Ecoute) {
            val (xl, xr, y0, h) = if (odd) listOf(0, 15, 6, 6) else listOf(1, 14, 7, 4)
            rect(xl, y0, 1, h, PixelRole.Onde)
            rect(xr, y0, 1, h, PixelRole.Onde)
        }
        if (state == MascotState.Reflechit) {
            listOf(13 to 4, 14 to 2, 15 to 0).take(frame % 4).forEach { (x, y) -> rect(x, y, 1, 1, PixelRole.Bulle) }
        }

        // En action, le personnage sautille d'un pixel.
        return if (state == MascotState.Agit && odd) out.map { it.copy(y = it.y - 1) } else out
    }
}
