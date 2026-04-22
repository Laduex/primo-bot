package dev.saseq.primobot.sales;

public interface SalesProvider {
    SalesPlatform platform();

    SalesAccountResult fetchTodayCumulative(SalesAccountConfig account, SalesFetchContext context);
}
