package yun.shan.mpsync.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.UUID;

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
        UUID uuid = player.getUniqueId();
        String[] argv = args.clone();
        // MpTextCommands.run 会读写房间状态：Folia 上玩家指令跑在玩家所在区域的线程，
        // 必须先回引擎线程。Paper 上指令本就在主线程，execute 就地执行，反馈仍是同一 tick。
        // 反馈直接发回去，理由同 PaperPlatform#chat（见类注释）。
        platform.execute(() -> {
            List<String> lines = MpTextCommands.run(uuid, operator, argv);
            for (String line : lines) player.sendMessage(line);
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return MpTextCommands.suggest(args, platform.onlineNames());
    }
}
