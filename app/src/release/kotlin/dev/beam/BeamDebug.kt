package dev.beam

import android.content.Context
import dev.beam.core.transport.Transport

/**
 * Release stub for the developer simulation tools.
 *
 * The simulator and the `:transport:fake` module it needs are absent from shipping builds, so a
 * release Beam has no code path that can fabricate a transfer. The API is kept identical to the
 * debug variant so the UI compiles against both.
 */
object BeamDebug {

    const val IS_SIMULATION_AVAILABLE = false

    fun extraTransports(context: Context): List<Transport>? = null

    data class Scenario(
        val id: String,
        val title: String,
        val description: String,
    )

    val scenarios: List<Scenario> = emptyList()

    suspend fun runScenario(scenario: Scenario, log: (String) -> Unit): Long = 0

    fun simulateIncomingTap(context: Context, container: BeamContainer): Boolean = false
}
