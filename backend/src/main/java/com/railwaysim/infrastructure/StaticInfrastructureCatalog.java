package com.railwaysim.infrastructure;

import com.railwaysim.config.SimulationProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StaticInfrastructureCatalog {

    private static final Logger logger = LoggerFactory.getLogger(StaticInfrastructureCatalog.class);

    private final OperationalLineData lineData;
    private final OperationalPowerData powerData;

    public StaticInfrastructureCatalog(OperationalLineData lineData, OperationalPowerData powerData) {
        this.lineData = lineData;
        this.powerData = powerData;
    }

    public StaticInfrastructureCatalog(
        SimulationProperties simulationProperties,
        SpreadsheetLineDataLoader spreadsheetLineDataLoader,
        YamlLineDataLoader yamlLineDataLoader,
        PowerConfigLoader powerConfigLoader
    ) {
        this(
            simulationProperties,
            spreadsheetLineDataLoader,
            yamlLineDataLoader,
            powerConfigLoader,
            null,
            null
        );
    }

    @Autowired
    public StaticInfrastructureCatalog(
        SimulationProperties simulationProperties,
        SpreadsheetLineDataLoader spreadsheetLineDataLoader,
        YamlLineDataLoader yamlLineDataLoader,
        PowerConfigLoader powerConfigLoader,
        JdbcLineDataLoader jdbcLineDataLoader,
        DatabaseLineDataImporter databaseLineDataImporter
    ) {
        try {
            lineData = loadLineData(
                simulationProperties,
                spreadsheetLineDataLoader,
                yamlLineDataLoader,
                jdbcLineDataLoader,
                databaseLineDataImporter
            );
            powerData = loadPowerData(simulationProperties, powerConfigLoader, lineData.lineLengthMeters());
            logger.info(
                "Loaded line data [{}] with {} segments, {} stations, {} switches; power profile has {} sections",
                lineData.lineName(),
                lineData.trackSegments().size(),
                lineData.stations().size(),
                lineData.switches().size(),
                powerData.sections().size()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load static railway infrastructure", exception);
        }
    }

    public OperationalLineData lineData() {
        return lineData;
    }

    public OperationalPowerData powerData() {
        return powerData;
    }

    private OperationalLineData loadLineData(
        SimulationProperties simulationProperties,
        SpreadsheetLineDataLoader spreadsheetLineDataLoader,
        YamlLineDataLoader yamlLineDataLoader,
        JdbcLineDataLoader jdbcLineDataLoader,
        DatabaseLineDataImporter databaseLineDataImporter
    ) throws IOException {
        String source = simulationProperties.getLineDataSource() == null
            ? "file"
            : simulationProperties.getLineDataSource().trim().toLowerCase(Locale.ROOT);
        if ("database".equals(source) || "db".equals(source)) {
            if (jdbcLineDataLoader == null || databaseLineDataImporter == null) {
                logger.warn("Database line data source requested without database loaders; falling back to file source");
                return loadLineDataFromFile(simulationProperties, spreadsheetLineDataLoader, yamlLineDataLoader);
            }
            if (simulationProperties.isLineDataAutoImport()) {
                databaseLineDataImporter.importWorkbook(
                    Path.of(simulationProperties.getLineDataPath()),
                    simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                );
            } else if (!databaseLineDataImporter.hasLine(simulationProperties.getLineId())) {
                throw new IllegalStateException(
                    "No line data found in database for line_id=" + simulationProperties.getLineId()
                );
            }
            return jdbcLineDataLoader.load(simulationProperties.getLineId());
        }
        return loadLineDataFromFile(simulationProperties, spreadsheetLineDataLoader, yamlLineDataLoader);
    }

    private OperationalLineData loadLineDataFromFile(
        SimulationProperties simulationProperties,
        SpreadsheetLineDataLoader spreadsheetLineDataLoader,
        YamlLineDataLoader yamlLineDataLoader
    ) throws IOException {
        Path lineDataPath = Path.of(simulationProperties.getLineDataPath());
        String fileName = lineDataPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
            return spreadsheetLineDataLoader.load(lineDataPath, simulationProperties.getDefaultSpeedLimitMetersPerSecond());
        }
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return yamlLineDataLoader.load(lineDataPath);
        }
        throw new IllegalArgumentException("Unsupported line data format: " + simulationProperties.getLineDataPath());
    }

    private OperationalPowerData loadPowerData(
        SimulationProperties simulationProperties,
        PowerConfigLoader powerConfigLoader,
        double lineLengthMeters
    ) throws IOException {
        return powerConfigLoader.load(Path.of(simulationProperties.getPowerConfigPath()), lineLengthMeters);
    }
}
