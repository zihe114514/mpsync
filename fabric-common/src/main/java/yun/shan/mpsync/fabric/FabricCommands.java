package yun.shan.mpsync.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import yun.shan.mpsync.command.MpTextCommands;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * {@code /mp <args...>} on Fabric: one greedy-string argument forwarded to the shared
 * {@link MpTextCommands}, so Paper and Fabric expose the exact same command surface and
 * the room rules live in one place.
 *
 * <p>The "is this an operator" test is injected per version: 1.21.11 replaced
 * {@code hasPermissionLevel(int)} with a {@code PermissionPredicate}, and that is the only
 * command-side API that moved between 1.20.1 and 1.21.11.
 */
public final class FabricCommands {

    private FabricCommands() {}

    /** @param isOperator level-2 (or higher) test for this Minecraft version */
    public static void register(Predicate<ServerCommandSource> isOperator) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("mp")
                    .executes(ctx -> run(ctx, isOperator, new String[0]))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                            .suggests(FabricCommands::suggest)
                            .executes(ctx -> run(ctx, isOperator,
                                    split(StringArgumentType.getString(ctx, "args")))));
            dispatcher.register(root);
        });
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        // The greedy argument holds every word, so completion works off the last one.
        String remaining = builder.getRemaining();
        String[] words = split(remaining);
        boolean trailingSpace = remaining.endsWith(" ");
        String[] forSuggest = trailingSpace ? append(words, "") : words;
        List<String> names = ctx.getSource().getServer() == null ? List.of()
                : List.of(ctx.getSource().getServer().getPlayerNames());
        int start = remaining.length() - (trailingSpace ? 0 : lastWordLength(remaining));
        SuggestionsBuilder at = builder.createOffset(builder.getStart() + start);
        for (String s : MpTextCommands.suggest(forSuggest, names)) at.suggest(s);
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

    private static int run(CommandContext<ServerCommandSource> ctx,
                           Predicate<ServerCommandSource> isOperator, String[] args)
            throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        boolean operator = isOperator.test(ctx.getSource());
        for (String line : MpTextCommands.run(player.getUuid(), operator, args)) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }
}
