package com.automotive.ecall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcallOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcallOrchestratorApplication.class, args);
    }
}
