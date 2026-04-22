package dev.saseq.primobot.sales;

import java.time.LocalDate;
import java.time.ZoneId;

public record SalesFetchContext(ZoneId zoneId, LocalDate reportDate) {
}
