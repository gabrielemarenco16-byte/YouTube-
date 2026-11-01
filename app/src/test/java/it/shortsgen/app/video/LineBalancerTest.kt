package it.shortsgen.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

class LineBalancerTest {
    /** Larghezza finta: 10 per carattere. */
    private val w: (String) -> Float = { it.length * 10f }

    private fun split(text: String, max: Float) = LineBalancer.lines(text.split(" "), max, w).map { it.joinToString(" ") }

    @Test
    fun staysOnOneLineWhenItFits() {
        assertEquals(listOf("THE THIRD HEART"), split("THE THIRD HEART", 200f))
    }

    @Test
    fun neverLeavesOneWordAloneOnTheSecondLine() {
        // un a-capo "a riempimento" darebbe "FOR MORE INCREDIBLE / CURIOSITIES"
        assertEquals(listOf("FOR MORE", "INCREDIBLE CURIOSITIES"), split("FOR MORE INCREDIBLE CURIOSITIES", 230f))
    }

    @Test
    fun balancesFourWords() {
        assertEquals(listOf("ABOUT OUR", "AMAZING PLANET!"), split("ABOUT OUR AMAZING PLANET!", 200f))
    }

    @Test
    fun usesThreeLinesInsteadOfCuttingWords() {
        // nessuna divisione in 2 righe sta in 120: si usano 3 righe, nessuna parola persa
        val lines = split("BIOLUMINESCENT SIPHONOPHORE COLONIES", 150f)
        assertEquals(listOf("BIOLUMINESCENT", "SIPHONOPHORE", "COLONIES"), lines)
    }

    @Test
    fun avoidsALineEndingOnAWeakWordWhenPossible() {
        // "SEE THE / GIANT OCTOPUS" finirebbe su "THE": meglio "SEE / THE GIANT OCTOPUS"
        assertEquals(listOf("SEE", "THE GIANT OCTOPUS"), split("SEE THE GIANT OCTOPUS", 180f))
    }

    @Test
    fun loneWordRuleWinsOverWeakEnding() {
        // l'unica alternativa senza parola sola in fondo finisce su "ITS": va bene così
        assertEquals(listOf("OF ITS", "SOFT BODY."), split("OF ITS SOFT BODY.", 110f))
    }
}
