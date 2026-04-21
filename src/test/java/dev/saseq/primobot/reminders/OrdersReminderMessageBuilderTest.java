package dev.saseq.primobot.reminders;

import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrdersReminderMessageBuilderTest {

    private final OrdersReminderMessageBuilder builder = new OrdersReminderMessageBuilder();

    @Test
    void resolvesGreetingByTimeBucket() {
        assertEquals("Good Morning", builder.resolveGreeting(LocalTime.of(5, 0)));
        assertEquals("Good Morning", builder.resolveGreeting(LocalTime.of(11, 59)));
        assertEquals("Good Afternoon", builder.resolveGreeting(LocalTime.of(12, 0)));
        assertEquals("Good Afternoon", builder.resolveGreeting(LocalTime.of(17, 59)));
        assertEquals("Good Evening", builder.resolveGreeting(LocalTime.of(18, 0)));
        assertEquals("Good Evening", builder.resolveGreeting(LocalTime.of(4, 59)));
    }

    @Test
    void buildsFriendlyReminderContent() {
        ThreadChannel thread = mock(ThreadChannel.class);
        when(thread.getName()).thenReturn("April 20 | Monday | Mechanika Order");
        when(thread.getId()).thenReturn("1495615105767837808");

        String message = builder.buildReminderMessage(
                "1494215754084651089",
                "Good Morning",
                "roastery-orders",
                "1478671501338214410",
                List.of(thread),
                "Thanks, Primo",
                "casual");

        assertTrue(message.contains("<@&1494215754084651089> Good Morning, team!"));
        assertTrue(message.contains("[April 20 | Monday | Mechanika Order](https://discord.com/channels/1478671501338214410/1495615105767837808)"));
        assertTrue(message.contains("please run `/completed`"));
        assertTrue(message.endsWith("Thanks, Primo"));
    }
}
