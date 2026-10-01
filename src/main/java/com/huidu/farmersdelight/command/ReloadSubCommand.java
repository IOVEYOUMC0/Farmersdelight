package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ReloadTarget;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.huidu.farmersdelight.command.CommandSupport.normalize;

final class ReloadSubCommand extends SubCommand {

    // Command tokens for tab-completion and usage, derived from ReloadTarget's primary aliases so a
    // new constant is offered automatically.
    private static final List<String> RELOAD_TARGETS = Arrays.stream(ReloadTarget.values())
            .map(ReloadTarget::eventReason)
            .toList();

    private final FarmersDelightPlugin plugin;
    // One reload at a time plus a short cooldown. A full reload blocks the tick thread for FD and then for
    // every addon on the following tick, so a second request is refused instead of stacking another stall.
    // The cooldown is read per call, so editing config.yml takes effect without a reload of its own.
    private final ReloadBusyGuard busyGuard;

    ReloadSubCommand(FarmersDelightPlugin plugin) {
        super("reload", List.of(), "farmersdelight.admin", "command.help_reload");
        this.plugin = plugin;
        // The plugin may be null: tab-completion is constructible without a server (FarmersDelightCommandTest
        // builds the command tree that way). execute() is the only path that needs the plugin, and it is never
        // reached in that setup, so the guard reads the cooldown defensively.
        this.busyGuard = new ReloadBusyGuard(
                () -> plugin == null ? 0L : plugin.reloadCooldownMillis(),
                System::currentTimeMillis,
                remainingSeconds -> {});
    }

    @Override
    void execute(CommandSender sender, String label, String[] args) {
        String token = args.length >= 2 ? normalize(args[1]) : "config";
        ReloadTarget target = ReloadTarget.fromCommand(token);
        if (target == null) {
            sendReloadUsage(sender);
            return;
        }

        // The guard remains held throughout worker preparation and server-thread publication.
        if (!busyGuard.begin(ReloadBusyGuard.messageTo(sender))) {
            return;
        }
        long started = System.nanoTime();
        plugin.reloadTargetAsync(target).whenComplete((ignored, error) -> {
            busyGuard.finish();
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Reload failed for " + target.eventReason(), error);
            }
            if (!plugin.isEnabled()) {
                return;
            }
            Runnable feedback = () -> {
                if (sender instanceof Player player && !player.isOnline()) {
                    return;
                }
                if (error != null) {
                    sender.sendMessage(I18n.getComponent("command.reload_failed", placeholders()));
                    return;
                }
                sender.sendMessage(I18n.getComponent("general.config_reloaded", placeholders()));
                Map<String, String> report = placeholders();
                report.put("total", String.valueOf((System.nanoTime() - started) / 1_000_000L));
                report.put("split", plugin.reloadTimingSummary());
                sender.sendMessage(I18n.getComponent("command.reload_report", report));
                int issues = plugin.reloadIssueCount();
                if (issues > 0) {
                    sender.sendMessage(I18n.getComponent("command.reload_report_issues",
                            Map.of("prefix", report.get("prefix"), "issues", String.valueOf(issues))));
                }
            };
            try {
                if (sender instanceof Player player) {
                    plugin.scheduler().runForEntity(player, feedback);
                } else {
                    plugin.scheduler().run(feedback);
                }
            } catch (RuntimeException stopped) {
                // No feedback is scheduled after shutdown.
            }
        });
    }

    @Override
    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }

        String partial = normalize(args[1]);
        List<String> completions = new ArrayList<>();
        for (String target : RELOAD_TARGETS) {
            if (target.startsWith(partial)) {
                completions.add(target);
            }
        }
        return completions;
    }

    /**
     * The values every command message needs. The brand prefix lives in one lang key
     * ({@code general.prefix}) and is injected here, so messages write {@code {prefix}} instead of each
     * carrying its own copy of the markup.
     */
    private static Map<String, String> placeholders() {
        Map<String, String> values = new HashMap<>();
        values.put("prefix", I18n.get("general.prefix"));
        return values;
    }

    private void sendReloadUsage(CommandSender sender) {
        sender.sendMessage(I18n.getComponent("command.reload_usage",
                Map.of("targets", String.join("|", RELOAD_TARGETS))));
    }
}
