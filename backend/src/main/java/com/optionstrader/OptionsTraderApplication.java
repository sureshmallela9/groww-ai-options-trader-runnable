package com.optionstrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OptionsTraderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OptionsTraderApplication.class, args);
    }
}