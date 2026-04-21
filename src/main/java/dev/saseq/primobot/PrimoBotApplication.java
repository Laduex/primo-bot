package dev.saseq.primobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PrimoBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrimoBotApplication.class, args);
    }
}
