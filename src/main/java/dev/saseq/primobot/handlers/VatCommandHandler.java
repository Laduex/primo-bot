package dev.saseq.primobot.handlers;

import dev.saseq.primo.core.vat.VatBasis;
import dev.saseq.primo.core.vat.VatCalculator;
import dev.saseq.primo.core.vat.VatResult;
import dev.saseq.primobot.commands.PrimoCommands;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;

@Component
public class VatCommandHandler {
    private static final BigDecimal FIXED_VAT_RATE = BigDecimal.valueOf(12);

    private final VatCalculator vatCalculator = new VatCalculator();

    public void handle(SlashCommandInteractionEvent event) {
        var amountOption = event.getOption(PrimoCommands.VAT_AMOUNT_OPTION);
        var basisOption = event.getOption(PrimoCommands.VAT_BASIS_OPTION);

        if (amountOption == null || basisOption == null) {
            event.reply("Missing required options. Use `/vat amount:<number> basis:<inclusive|exclusive>`.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountOption.getAsString());
        } catch (NumberFormatException ex) {
            event.reply("Invalid amount format. Please use a valid number.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        VatBasis basis;
        try {
            basis = VatBasis.fromInput(basisOption.getAsString());
        } catch (IllegalArgumentException ex) {
            event.reply(ex.getMessage()).setEphemeral(true).queue();
            return;
        }

        VatResult result;
        try {
            result = vatCalculator.calculate(amount, FIXED_VAT_RATE, basis);
        } catch (IllegalArgumentException ex) {
            event.reply(ex.getMessage()).setEphemeral(true).queue();
            return;
        }

        String response = """
                **Primo VAT Calculator**
                Basis: %s
                Rate: %s%% (fixed)
                Scope: PH standard VATable transactions only (excludes VAT-exempt and zero-rated sales)
                Net (VAT Exclusive): %s
                VAT: %s
                Gross (VAT Inclusive): %s
                """.formatted(
                result.basisLabel(),
                formatNumber(FIXED_VAT_RATE),
                formatMoney(result.netAmount()),
                formatMoney(result.vatAmount()),
                formatMoney(result.grossAmount())
        );

        event.reply(response).queue();
    }

    private String formatMoney(BigDecimal value) {
        return "PHP " + formatNumber(value);
    }

    private String formatNumber(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00");
        return format.format(value);
    }
}
