package com.railwaysim.localnet.powerpoints;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerPointTableLoaderTests {

    @TempDir
    Path tempDir;

    @Test
    void loadsYamlPointTable() throws Exception {
        Path file = tempDir.resolve("points.yaml");
        Files.writeString(file, """
            points:
              - pointId: P01_VOLTAGE
                direction: READ
                dataType: DOUBLE
                address: SIM:P01:VOLTAGE
                defaultValue: "1500"
                domainTarget: THIRD_RAIL_SECTION:P01:voltage
                quality: GOOD
            """);

        List<PowerPointDefinition> definitions = new PowerPointTableLoader().load(file.toString());

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).pointId()).isEqualTo("P01_VOLTAGE");
        assertThat(definitions.get(0).readPoint()).isTrue();
        assertThat(definitions.get(0).targetType()).isEqualTo("THIRD_RAIL_SECTION");
        assertThat(definitions.get(0).targetId()).isEqualTo("P01");
        assertThat(definitions.get(0).targetField()).isEqualTo("voltage");
        assertThat(definitions.get(0).quality()).isEqualTo("GOOD");
    }

    @Test
    void loadsCsvPointTable() throws Exception {
        Path file = tempDir.resolve("points.csv");
        Files.writeString(file, """
            pointId,name,direction,dataType,address,scale,defaultValue,domainTarget,quality,operationType,desiredState
            ISO_CMD,隔离开关命令,WRITE,BOOLEAN,SIM:ISO:CMD,1,CLOSED,ISOLATOR:ISO-P01-A:state,GOOD,SET_ISOLATOR,OPEN
            """);

        List<PowerPointDefinition> definitions = new PowerPointTableLoader().load(file.toString());

        assertThat(definitions).hasSize(1);
        PowerPointDefinition definition = definitions.get(0);
        assertThat(definition.pointId()).isEqualTo("ISO_CMD");
        assertThat(definition.writePoint()).isTrue();
        assertThat(definition.targetType()).isEqualTo("ISOLATOR");
        assertThat(definition.targetId()).isEqualTo("ISO-P01-A");
        assertThat(definition.targetField()).isEqualTo("state");
        assertThat(definition.operationType()).isEqualTo("SET_ISOLATOR");
        assertThat(definition.desiredState()).isEqualTo("OPEN");
    }
}
