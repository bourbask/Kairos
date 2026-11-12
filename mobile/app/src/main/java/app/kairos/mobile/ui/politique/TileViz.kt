package app.kairos.mobile.ui.politique

import androidx.compose.ui.geometry.Offset
import app.kairos.mobile.ui.politique.model.PolData
import app.kairos.mobile.ui.politique.screens.Measure
import app.kairos.mobile.ui.politique.screens.Turn
import app.kairos.mobile.ui.politique.screens.VoteRow
import app.kairos.mobile.ui.politique.screens.aideOf
import app.kairos.mobile.ui.politique.screens.affairesOf
import app.kairos.mobile.ui.politique.screens.axisBounds
import app.kairos.mobile.ui.politique.screens.axisPos
import app.kairos.mobile.ui.politique.screens.bilanState
import app.kairos.mobile.ui.politique.screens.budgetsState
import app.kairos.mobile.ui.politique.screens.campsOf
import app.kairos.mobile.ui.politique.screens.claimsOf
import app.kairos.mobile.ui.politique.screens.isDom
import app.kairos.mobile.ui.politique.screens.mediasState
import app.kairos.mobile.ui.politique.screens.nameOf
import app.kairos.mobile.ui.politique.screens.rhetOf
import app.kairos.mobile.ui.politique.screens.scrutinsOf
import app.kairos.mobile.ui.politique.screens.villesModel
import kotlin.math.abs

/** Couleur d'une marque : celle d'une identité ([Mark.hex]) ou une couleur d'état neutre. */
enum class Ink { Identity, Muted, Line, Ink, Pour, Contre, Partiel, PartielBg, Solide, Fragile, Low, High, Night }

/**
 * [Box] et [Hollow] (contour) : rectangle (x, y, w, h) ; [Dot] : centre (x, y), rayon w (= h) ;
 * [Dash] : segment pointillé de (x, y) à (x + w, y + h).
 */
enum class MarkShape { Box, Hollow, Dot, Dash }

data class Mark(val shape: MarkShape, val x: Float, val y: Float, val w: Float, val h: Float, val ink: Ink, val hex: String? = null)

/** Mini-graphique d'une tuile d'accueil, dans un repère [w] × [h] ; [regions] : fond de carte (anneaux, même repère). */
class TileViz(val w: Float, val h: Float, val marks: List<Mark>, val regions: List<List<Offset>> = emptyList())

private const val W = 140f
private const val H = 54f

private fun PolData.hexOf(who: String): String? = (idOf(who) ?: idOf(nameOf(who)))?.let { byId(it)?.couleur }

private fun viz(marks: List<Mark>, w: Float = W, h: Float = H) = marks.takeIf { it.isNotEmpty() }?.let { TileViz(w, h, it) }

/** Mini-graphique d'une rubrique, tiré des mêmes modèles que son écran ; rien à montrer : null. */
fun tileViz(data: PolData, id: String): TileViz? = when (id) {
    "budgets" -> {
        val st = budgetsState(data)
        val (lo, hi) = axisBounds(st.axis, st.cands.mapNotNull { it.debt } + listOfNotNull(st.ref))
        fun x(v: Double) = 6f + axisPos(v, lo, hi).toFloat() * (W - 12f)
        val dots = st.cands.mapIndexed { i, p -> Mark(MarkShape.Dot, x(p.debt!!), 8f + (i % 4) * 12f, 5f, 5f, Ink.Identity, data.hexOf(p.who)) }
        viz(listOfNotNull(st.ref?.let { Mark(MarkShape.Dash, x(it), 2f, 0f, H - 4f, Ink.Muted) }).takeIf { dots.isNotEmpty() }.orEmpty() + dots)
    }
    "niveau-de-vie" -> {
        val m = data.decode<Measure>("niveau-de-vie", "MEASURES").filter { it.hh != 0.0 }.sortedByDescending { it.hh }
        val mx = m.maxOfOrNull { abs(it.hh) } ?: 0.0
        val bw = (W - 4f) / m.size.coerceAtLeast(1)
        viz(
            if (mx <= 0) emptyList() else m.mapIndexed { i, r ->
                val bh = (abs(r.hh) / mx).toFloat() * (H / 2 - 2f)
                Mark(MarkShape.Box, 2f + i * bw + 2f, if (r.hh > 0) H / 2 - bh else H / 2, bw - 4f, bh, Ink.Identity, data.hexOf(r.who))
            },
        )
    }
    "votes" -> viz(
        data.decode<VoteRow>("votes", "AN_V").take(4).flatMapIndexed { r, v ->
            v.c.mapIndexed { i, c ->
                val ink = when (c.firstOrNull()) { "P" -> Ink.Pour; "C" -> Ink.Contre; "A" -> Ink.Partiel; "S" -> Ink.Muted; else -> Ink.Line }
                Mark(MarkShape.Box, i * 15.5f, r * 13.5f, 12f, 10f, ink)
            }
        },
    )
    "revirements" -> {
        val rv = data.decode<Turn>("revirements", "RV")
        var x = 0f
        viz(
            listOf("R" to Ink.Contre, "I" to Ink.Partiel, "C" to Ink.Pour).mapNotNull { (k, ink) ->
                val n = rv.count { it.k == k }.takeIf { it > 0 } ?: return@mapNotNull null
                val w = n.toFloat() / rv.size * W
                Mark(MarkShape.Box, x, H - 14f, w - 3f, 12f, ink).also { x += w }
            },
        )
    }
    "bilan" -> {
        val rows = bilanState(data).ofce.filter { !it.total }.take(6)
        val mx = rows.maxOfOrNull { abs(it.v) }?.takeIf { it > 0 } ?: 1.0
        viz(
            rows.mapIndexed { i, r ->
                val w = (abs(r.v) / mx).toFloat() * 60f
                Mark(MarkShape.Box, if (r.v >= 0) 70f else 70f - w, i * 9f, w, 6f, if (r.v >= 0) Ink.High else Ink.Low)
            }.let { if (it.isEmpty()) it else it + Mark(MarkShape.Dash, 70f, 0f, 0f, H, Ink.Ink) },
        )
    }
    // Deux cartes à retourner, dès qu'un procédé est publié.
    "rhetorique" -> viz(if (rhetOf(data).isEmpty()) emptyList() else listOf(Mark(MarkShape.Box, 8f, 6f, 44f, 46f, Ink.PartielBg), Mark(MarkShape.Box, 40f, 4f, 44f, 48f, Ink.Night)))
    // L'échelle des cinq étapes d'une procédure, dès qu'une affaire est publiée.
    "probite" -> viz(if (affairesOf(data).isEmpty()) emptyList() else (0..4).map { i -> Mark(MarkShape.Box, i * 28f, H - 10f - i * 9f, 24f, 10f + i * 9f, if (i < 2) Ink.Ink else Ink.Line) })
    "argent" -> {
        val a = aideOf(data)
        viz(a.rows.take(6).mapIndexedNotNull { i, r -> r.v?.let { Mark(MarkShape.Box, 0f, i * 9f, (it / a.max).toFloat().coerceIn(0f, 1f) * W, 6f, Ink.Identity, data.hexOf(r.l)) } })
    }
    "medias" -> {
        val aud = mediasState(data).audience
        fun bar(x: Float, v: Double?, ink: Ink) = v?.let { (it / aud.max).toFloat().coerceIn(0f, 1f) * H }?.let { Mark(MarkShape.Box, x, H - it, 13f, it, ink) }
        viz(aud.rows.take(4).flatMapIndexed { i, r -> listOfNotNull(bar(i * 35f, r.before, Ink.Muted), bar(i * 35f + 15f, r.now, Ink.Pour)) })
    }
    "villes" -> {
        val m = villesModel(data)
        if (!m.hasMap || m.placed.isEmpty()) null else {
            val o = Offset(m.viewBox.left, m.viewBox.top)
            val r = m.viewBox.width * 0.022f
            TileViz(
                m.viewBox.width, m.viewBox.height,
                m.placed.filter { !isDom(it.c.region) }.map { Mark(MarkShape.Dot, it.at.x - o.x, it.at.y - o.y, r, r, Ink.Identity, data.byId(it.c.qui)?.couleur) },
                m.regions.flatMap { g -> g.rings.map { ring -> ring.map { it - o } } },
            )
        }
    }
    "dit-vote" -> viz(
        scrutinsOf(data).takeLast(6).flatMapIndexed { r, s ->
            s.verdicts.take(10).mapIndexed { i, v ->
                val ink = when (v.v) { "coherent" -> Ink.Solide; "ambigu" -> Ink.Partiel; "ecart" -> Ink.Fragile; else -> null }
                Mark(if (ink == null) MarkShape.Hollow else MarkShape.Box, i * 14f, r * 9f, 11f, 6.5f, ink ?: Ink.Muted)
            }
        },
    )
    "bulle" -> viz(
        campsOf(data, claimsOf(data)).mapIndexed { i, c -> Mark(MarkShape.Dot, 28f + i * 24f, 32f + if (i % 2 == 1) -6f else 6f, 20f, 20f, Ink.Identity, data.hexOf(c)) },
        104f, 64f,
    )
    else -> null
}
