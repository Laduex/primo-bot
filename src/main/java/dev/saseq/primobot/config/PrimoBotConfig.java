package dev.saseq.primobot.config;

import dev.saseq.primobot.commands.PrimoCommands;
import dev.saseq.primobot.commands.PrimoSlashCommandListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class PrimoBotConfig {

    @Bean
    public JDA jda(@Value("${DISCORD_TOKEN:}") String token,
                   @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
                   PrimoSlashCommandListener primoSlashCommandListener) throws InterruptedException {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("The environment variable DISCORD_TOKEN is not set.");
        }

        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(primoSlashCommandListener)
                .build()
                .awaitReady();

        CommandData vatCommand = PrimoCommands.buildVatSlashCommand();
        CommandData orderCommand = PrimoCommands.buildOrderSlashCommand();
        CommandData orderRemindCommand = PrimoCommands.buildOrderRemindSlashCommand();
        CommandData completedCommand = PrimoCommands.buildCompletedSlashCommand();
        CommandData ordersReminderCommand = PrimoCommands.buildOrdersReminderSlashCommand();
        CommandData salesReportCommand = PrimoCommands.buildSalesReportSlashCommand();
        CommandData salesCommand = PrimoCommands.buildSalesSlashCommand();

        if (defaultGuildId != null && !defaultGuildId.isBlank()) {
            Guild guild = jda.getGuildById(defaultGuildId);
            if (guild != null) {
                syncGuildCommands(guild, vatCommand, orderCommand, orderRemindCommand, completedCommand, ordersReminderCommand, salesReportCommand, salesCommand);
                deleteGlobalCommandsByName(jda, Set.of(
                        PrimoCommands.COMMAND_VAT,
                        PrimoCommands.COMMAND_ORDER,
                        PrimoCommands.COMMAND_ORDER_REMIND,
                        PrimoCommands.COMMAND_COMPLETED,
                        PrimoCommands.COMMAND_ORDERS_REMINDER,
                        PrimoCommands.COMMAND_SALES_REPORT,
                        PrimoCommands.COMMAND_SALES,
                        "recipe",
                        "supplier",
                        "primo"
                ));
                return jda;
            }
        }

        syncGlobalVatCommand(jda, vatCommand);
        jda.upsertCommand(orderCommand).queue();
        jda.upsertCommand(orderRemindCommand).queue();
        jda.upsertCommand(completedCommand).queue();
        jda.upsertCommand(ordersReminderCommand).queue();
        jda.upsertCommand(salesReportCommand).queue();
        jda.upsertCommand(salesCommand).queue();
        deleteGlobalCommandsByName(jda, Set.of("recipe", "supplier", "primo"));
        return jda;
    }

    private void syncGlobalVatCommand(JDA jda, CommandData vatCommand) {
        jda.upsertCommand(vatCommand).queue();
    }

    private void syncGuildCommands(Guild guild,
                                   CommandData vatCommand,
                                   CommandData orderCommand,
                                   CommandData orderRemindCommand,
                                   CommandData completedCommand,
                                   CommandData ordersReminderCommand,
                                   CommandData salesReportCommand,
                                   CommandData salesCommand) {
        guild.upsertCommand(vatCommand).queue();
        guild.upsertCommand(orderCommand).queue();
        guild.upsertCommand(orderRemindCommand).queue();
        guild.upsertCommand(completedCommand).queue();
        guild.upsertCommand(ordersReminderCommand).queue();
        guild.upsertCommand(salesReportCommand).queue();
        guild.upsertCommand(salesCommand).queue();
        deleteGuildCommandsByName(guild, Set.of("recipe", "supplier", "primo"));
    }

    private void deleteGlobalCommandsByName(JDA jda, Set<String> commandNames) {
        jda.retrieveCommands().queue(commands ->
                commands.stream()
                        .filter(command -> commandNames.contains(command.getName()))
                        .forEach(command -> command.delete().queue())
        );
    }

    private void deleteGuildCommandsByName(Guild guild, Set<String> commandNames) {
        guild.retrieveCommands().queue(commands ->
                commands.stream()
                        .filter(command -> commandNames.contains(command.getName()))
                        .forEach(command -> command.delete().queue())
        );
    }
}
