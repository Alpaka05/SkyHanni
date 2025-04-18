package at.hannibal2.skyhanni.features.garden.pests

import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.InventoryFullyOpenedEvent
import at.hannibal2.skyhanni.events.IslandChangeEvent
import at.hannibal2.skyhanni.events.ItemInHandChangeEvent
import at.hannibal2.skyhanni.events.LorenzChatEvent
import at.hannibal2.skyhanni.events.LorenzKeyPressEvent
import at.hannibal2.skyhanni.events.LorenzRenderWorldEvent
import at.hannibal2.skyhanni.events.ScoreboardChangeEvent
import at.hannibal2.skyhanni.events.garden.pests.PestSpawnEvent
import at.hannibal2.skyhanni.features.garden.GardenAPI
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI.isPlayerInside
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI.name
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI.pests
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI.renderPlot
import at.hannibal2.skyhanni.features.garden.GardenPlotAPI.sendTeleportTo
import at.hannibal2.skyhanni.test.GriffinUtils.drawWaypointFilled
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.ItemUtils.getLore
import at.hannibal2.skyhanni.utils.LocationUtils.distanceSqToPlayer
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.NEUItems
import at.hannibal2.skyhanni.utils.NumberUtil.formatNumber
import at.hannibal2.skyhanni.utils.RenderUtils.drawDynamicText
import at.hannibal2.skyhanni.utils.RenderUtils.exactPlayerEyeLocation
import at.hannibal2.skyhanni.utils.RenderUtils.renderRenderables
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.StringUtils
import at.hannibal2.skyhanni.utils.StringUtils.matchMatcher
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.seconds

class PestFinder {

    private val config get() = PestAPI.config.pestFinder

    private val patternGroup = RepoPattern.group("garden.pests.finder")
    private val pestsInScoreboardPattern by patternGroup.pattern(
        "scoreboard",
        " §7⏣ §[ac]The Garden §4§lൠ§7 x(?<pests>.*)"
    )
    private val pestInventoryPattern by patternGroup.pattern(
        "inventory",
        "§4§lൠ §cThis plot has §6(?<amount>\\d) Pests?§c!"
    )

    private var display = emptyList<Renderable>()
    private var lastTimeVacuumHold = SimpleTimeMark.farPast()

    @SubscribeEvent
    fun onPestSpawn(event: PestSpawnEvent) {
        if (!isEnabled()) return
        PestSpawnTimer.lastSpawnTime = SimpleTimeMark.now()
        val plot = GardenPlotAPI.getPlotByName(event.plotName)
        if (plot == null) {
            ChatUtils.userError("Open Desk to load plot names and pest locations!")
            return
        }
        plot.pests += event.amountPests
        update()
    }

    @SubscribeEvent
    fun onInventoryOpen(event: InventoryFullyOpenedEvent) {
        if (!isEnabled()) return
        if (event.inventoryName != "Configure Plots") return

        for (plot in GardenPlotAPI.plots) {
            plot.pests = 0
            val item = event.inventoryItems[plot.inventorySlot] ?: continue
            for (line in item.getLore()) {
                pestInventoryPattern.matchMatcher(line) {
                    plot.pests = group("amount").formatNumber().toInt()
                }
            }
        }
        update()
    }

    private fun update() {
        if (isEnabled()) {
            display = drawDisplay()
        }
    }

    private fun drawDisplay() = buildList {
        val totalAmount = getPlotsWithPests().sumOf { it.pests }
        if (totalAmount != PestAPI.scoreboardPests) {
            add(Renderable.string("§cIncorrect pest amount!"))
            add(Renderable.string("§eOpen Configure Plots Menu!"))
            return@buildList
        }

        add(Renderable.string("§eTotal pests in garden: §c${totalAmount}§7/§c8"))

        for (plot in getPlotsWithPests()) {
            val pests = plot.pests
            val plotName = plot.name
            val pestsName = StringUtils.pluralize(pests, "pest", withNumber = true)
            val renderable = Renderable.clickAndHover(
                "§c$pestsName §7in §b$plotName",
                listOf(
                    "§7Pests Found: §e$pests",
                    "§7In plot §b$plotName",
                    "",
                    "§eClick here to warp!"
                ),
                onClick = {
                    plot.sendTeleportTo()
                }
            )
            add(renderable)
        }
    }

    @SubscribeEvent
    fun onIslandChange(event: IslandChangeEvent) {
        update()
    }

    @SubscribeEvent
    fun onChat(event: LorenzChatEvent) {
        if (!isEnabled()) return
        if (event.message == "§cThere are no pests in your Garden right now! Keep farming!") {
            GardenPlotAPI.plots.forEach {
                it.pests = 0
            }
            update()
        }
    }

    @SubscribeEvent
    fun onScoreboardChange(event: ScoreboardChangeEvent) {
        if (!GardenAPI.inGarden()) return

        var newPests = 0
        for (line in event.newList) {
            pestsInScoreboardPattern.matchMatcher(line) {
                newPests = group("pests").formatNumber().toInt()
            }
        }

        if (newPests != PestAPI.scoreboardPests) {
            removePests(PestAPI.scoreboardPests - newPests)
            PestAPI.scoreboardPests = newPests
            update()
        }

        resetAllPests(newPests)
    }

    // Auto fixing plots marked as pests when killing all pests without SkyHanni earlier.
    private fun resetAllPests(newPests: Int) {
        if (newPests != 0) return

        var fixed = false
        for (plot in GardenPlotAPI.plots) {
            if (plot.pests > 0) {
                fixed = true
                plot.pests = 0
            }
        }
        if (fixed) {
            ChatUtils.debug("Auto fixed all plots with pests.")
        }
    }

    private fun removePests(removedPests: Int) {
        if (!isEnabled()) return
        if (removedPests < 1) return
        repeat(removedPests) {
            removeNearestPest()
        }
    }

    private fun getNearestInfestedPest() = getPlotsWithPests().minByOrNull { it.middle.distanceSqToPlayer() }

    private fun removeNearestPest() {
        val plot = getNearestInfestedPest() ?: run {
            ChatUtils.error("Can not remove nearest pest: No infested plots detected.")
            return
        }
        plot.pests--
    }

    @SubscribeEvent
    fun onRenderOverlay(event: GuiRenderEvent) {
        if (!isEnabled()) return
        if (!config.showDisplay) return
        if (config.onlyWithVacuum && !PestAPI.hasVacuumInHand()) return

        if (GardenAPI.inGarden() && config.showDisplay) {
            config.position.renderRenderables(display, posLabel = "Pest Finder")
        }
    }

    private fun getPlotsWithPests() = GardenPlotAPI.plots.filter { it.pests > 0 }

    // priority to low so that this happens after other renderPlot calls.
    @SubscribeEvent(priority = EventPriority.LOW)
    fun onRenderWorld(event: LorenzRenderWorldEvent) {
        if (!isEnabled()) return
        if (!config.showPlotInWorld) return
        if (config.onlyWithVacuum && !PestAPI.hasVacuumInHand() && (lastTimeVacuumHold.passedSince() > config.showBorderForSeconds.seconds)) return

        val playerLocation = event.exactPlayerEyeLocation()
        for (plot in getPlotsWithPests()) {
            if (plot.isPlayerInside()) {
                event.renderPlot(plot, LorenzColor.RED.toColor(), LorenzColor.DARK_RED.toColor())
                continue
            }
            event.renderPlot(plot, LorenzColor.GOLD.toColor(), LorenzColor.RED.toColor())

            val pestsName = StringUtils.pluralize(plot.pests, "pest", withNumber = true)
            val plotName = plot.name
            val middle = plot.middle
            val location = playerLocation.copy(x = middle.x, z = middle.z)
            event.drawWaypointFilled(location, LorenzColor.RED.toColor())
            event.drawDynamicText(location, "§c$pestsName §7in §b$plotName", 1.5)
        }
    }

    private var lastKeyPress = SimpleTimeMark.farPast()

    @SubscribeEvent
    fun onKeyClick(event: LorenzKeyPressEvent) {
        if (!GardenAPI.inGarden()) return
        if (Minecraft.getMinecraft().currentScreen != null) return
        if (NEUItems.neuHasFocus()) return

        if (event.keyCode != config.teleportHotkey) return
        if (lastKeyPress.passedSince() < 2.seconds) return
        lastKeyPress = SimpleTimeMark.now()

        val plot = getNearestInfestedPest() ?: run {
            ChatUtils.userError("No infested plots detected to warp to!")
            return
        }

        if (plot.isPlayerInside()) {
            ChatUtils.userError("You're already in an infested plot!")
            return
        }

        plot.sendTeleportTo()
    }

    @SubscribeEvent
    fun onItemInHandChange(event: ItemInHandChangeEvent) {
        if (!isEnabled()) return
        if (!config.showPlotInWorld) return
        if (event.oldItem !in PestAPI.vacuumVariants) return
        lastTimeVacuumHold = SimpleTimeMark.now()
    }

    fun isEnabled() = GardenAPI.inGarden() && (config.showDisplay || config.showPlotInWorld)
}
