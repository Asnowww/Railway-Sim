package com.railwaysim;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:railway-sim;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "railway.simulation.line-data-path=../database/线路数据(1).xls",
    "railway.simulation.power-config-path=../config/power_third_rail.yaml"
})
class RailwaySimApplicationTests {

    @Test
    void contextLoads() {
    }
}
