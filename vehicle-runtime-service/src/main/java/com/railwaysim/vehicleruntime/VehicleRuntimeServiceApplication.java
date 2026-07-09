package com.railwaysim.vehicleruntime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VehicleRuntimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VehicleRuntimeServiceApplication.class, args);
    }
}
