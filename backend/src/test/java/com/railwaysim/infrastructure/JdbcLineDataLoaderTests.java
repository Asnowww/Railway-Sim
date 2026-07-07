package com.railwaysim.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class JdbcLineDataLoaderTests {

    @Test
    void importWorkbookAndReloadLineDataFromDatabase() throws Exception {
        Path workbookPath = Path.of("../database/线路数据(1).xls");
        Assumptions.assumeTrue(Files.exists(workbookPath));

        DriverManagerDataSource dataSource = dataSource();
        createSchema(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        DatabaseLineDataImporter importer = new DatabaseLineDataImporter(
            jdbcTemplate,
            new SpreadsheetLineDataLoader(),
            objectMapper
        );
        JdbcLineDataLoader loader = new JdbcLineDataLoader(jdbcTemplate, objectMapper);

        OperationalLineData imported = importer.importWorkbook(workbookPath, 22.2);
        OperationalLineData reloaded = loader.load(imported.lineId());

        assertThat(reloaded.lineId()).isEqualTo(imported.lineId());
        assertThat(reloaded.trackSegments()).hasSameSizeAs(imported.trackSegments());
        assertThat(reloaded.speedLimitZones()).hasSameSizeAs(imported.speedLimitZones());
        assertThat(reloaded.gradientZones()).hasSameSizeAs(imported.gradientZones());
        assertThat(reloaded.stations()).hasSameSizeAs(imported.stations());
        assertThat(reloaded.platforms()).hasSameSizeAs(imported.platforms());
        assertThat(reloaded.signals()).hasSameSizeAs(imported.signals());
        assertThat(reloaded.balises()).hasSameSizeAs(imported.balises());
        assertThat(reloaded.routes()).hasSameSizeAs(imported.routes());
        assertThat(reloaded.lineLengthMeters()).isEqualTo(imported.lineLengthMeters());
    }

    private DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:line-data-loader;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSchema(DriverManagerDataSource dataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(true);
        populator.addScript(new FileSystemResource("../database/schema.sql"));
        populator.execute(dataSource);
    }
}
