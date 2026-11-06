package yun.shan.mpsync.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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
 * {@code /mp ...} on 26.1.2. Same shared {@link MpTextCommands} as every other target; only
 * the Brigadier plumbing and the permission check use official names ({@code PermissionSet}
 * replaced the old integer permission levels).
 */
public final class MpCommands {

    private MpCommands() {}

    private static final Permission LEVEL_2 = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);

    /** 这个指令来源是不是管理员（等级 2 以上）。/mp 指令和平台层的 isOperator(UUID) 共用这一个判断。 */
    public static boolean isOperator(CommandSourceStack source) {
        return source != null && source.permissions().hasPermission(LEVEL_2);
    }

    /**
     * 必须在 {@code onInitialize} 里调用，不能放进 {@code SERVER_STARTING}。
     *
     * <p>Fabric 在服务器<b>构建指令表</b>时调用已登记的 {@link CommandRegistrationCallback}；专用服务器是
     * 先加载数据包、建好指令表，然后才进入 {@code SERVER_STARTING}。在那里才登记回调就错过了这一次，
     * {@code /mp} 不会出现在指令表里（客户端输入时提示「未知或不完整的命令」），要等一次 {@code /reload}
     * 重建指令表才有。所以这里不依赖任何服务器启动后才有的对象：补全直接用指令来源所在的服务器。</p>
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mp")
                    .executes(ctx -> run(ctx, new String[0]))
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests(MpCommands::suggest)
                            .executes(ctx -> run(ctx, split(StringArgumentType.getString(ctx, "args")))));
            dispatcher.register(root);
        });
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
