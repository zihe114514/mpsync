package yun.shan.mpsync.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;

/** {@code /mp ...} on Paper — the same shared implementation Fabric registers. */
public final class MpCommand implements CommandExecutor, TabCompleter {

    private final PaperPlatform platform;

    public MpCommand(PaperPlatform platform) {
        this.platform = platform;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("一起听房间只能由玩家操作");
            return true;
        }
        boolean operator = sender.isOp() || sender.hasPermission("mpsync.admin");
        for (String line : MpTextCommands.run(player.getUniqueId(), operator, args)) {
            sender.sendMessage(line);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return MpTextCommands.suggest(args, platform.onlineNames());
    }
}
