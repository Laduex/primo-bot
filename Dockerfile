FROM maven:3.9.6-amazoncorretto-17 AS build

WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM amazoncorretto:17-alpine
WORKDIR /app

COPY --from=build /app/target/primo-bot-1.0.0.jar app.jar

ENV DISCORD_TOKEN=""
ENV DISCORD_GUILD_ID=""
ENV ORDER_REMINDER_CONFIG_PATH="/data/orders-reminder-config.json"

ENTRYPOINT ["java", "-jar", "app.jar"]
