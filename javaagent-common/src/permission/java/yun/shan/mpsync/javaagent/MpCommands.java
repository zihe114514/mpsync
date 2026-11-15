package yun.shan.mpsync.javaagent;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 原版服务端的 {@code /mp} 指令，命令逻辑复用 {@link MpTextCommands}。
 */
public final class MpCommands {

    private static final Permission LEVEL_2 = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    private MpCommands() {
    }

    /**
     * 这个指令来源是不是管理员（等级 2 以上）。/mp 指令和平台层的 isOperator(UUID) 共用这一个判断。
     */
    public static boolean isOperator(CommandSourceStack source) {
        return source != null && source.permissions().hasPermission(LEVEL_2);
    }

    /**
     * 在服务端构建指令表时注册命令，补全信息从指令来源获取。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mp")
            .executes(ctx -> run(ctx, new String[0]))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .suggests(MpCommands::suggest)
                .executes(ctx -> run(ctx, split(StringArgumentType.getString(ctx, "args")))));
        dispatcher.register(root);
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] words = split(remaining);
        boolean trailingSpace = remaining.endsWith(" ");
        String[] forSuggest = trailingSpace ? append(words, "") : words;
        int start = remaining.length() - (trailingSpace ? 0 : lastWordLength(remaining));
        SuggestionsBuilder at = builder.createOffset(builder.getStart() + start);
        List<String> names = ctx.getSource().getServer() == null ? List.of()
            : List.of(ctx.getSource().getServer().getPlayerNames());
        // 管理员额外补全管理员子命令（status / idle / ban …），判断和执行 /mp 时同一个。
        for (String s : MpTextCommands.suggest(forSuggest, names, isOperator(ctx.getSource()))) at.suggest(s);
        return at.buildFuture();
    }

    private static int lastWordLength(String s) {
        int i = s.lastIndexOf(' ');
        return i < 0 ? s.length() : s.length() - i - 1;
    }

    private static String[] append(String[] arr, String extra) {
        String[] out = new String[arr.length + 1];
        System.arraycopy(arr, 0, out, 0, arr.length);
        out[arr.length] = extra;
        return out;
    }

    private static String[] split(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String[] args)
        throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean operator = isOperator(ctx.getSource());
        for (String line : MpTextCommands.run(player.getUUID(), operator, args)) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}

