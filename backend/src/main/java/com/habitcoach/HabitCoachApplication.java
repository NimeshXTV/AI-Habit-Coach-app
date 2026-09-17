package com.habitcoach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HabitCoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(HabitCoachApplication.class, args);
    }
}
