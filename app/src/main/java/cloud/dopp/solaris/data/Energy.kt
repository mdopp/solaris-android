package cloud.dopp.solaris.data

/**
 * One current-power flow leg from `/api/portal/energy` — PV / Haus / Netz / Akku.
 * `watts` is the signed live reading (W); `sense` is the leg kind:
 * `supply` (PV), `draw` (house), `grid` (+import / -export),
 * `battery` (+charge / -discharge).
 */
data class EnergyFlow(
    val label: String,
    val watts: Double?,
    val unit: String,
    val sense: String,
    val entityId: String? = null,
)

/** The house energy picture (the `flow` legs; totals/circuits omitted for the widget). */
data class Energy(val flow: List<EnergyFlow>) {
    val pv get() = flow.firstOrNull { it.sense == "supply" }
    val house get() = flow.firstOrNull { it.sense == "draw" }
    val grid get() = flow.firstOrNull { it.sense == "grid" }
    val battery get() = flow.firstOrNull { it.sense == "battery" }
}
