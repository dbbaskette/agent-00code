package com.agent00code;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Agent00CodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(Agent00CodeApplication.class, args);
    }
}
