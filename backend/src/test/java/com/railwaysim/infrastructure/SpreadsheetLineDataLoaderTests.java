package com.railwaysim.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

class SpreadsheetLineDataLoaderTests {

    private final SpreadsheetLineDataLoader loader = new SpreadsheetLineDataLoader();

    @Test
    void loadBuildsOperationalLineDataFromWorkbook() throws IOException {
        Path workbookPath = Files.createTempFile("line-data-", ".xls");
        try {
            writeSampleWorkbook(workbookPath);

            OperationalLineData lineData = loader.load(workbookPath, 22.2);

            assertThat(lineData.lineId()).startsWith("line-data-");
            assertThat(lineData.trackSegments()).hasSize(2);
            assertThat(lineData.trackSegments().get(0).id()).isEqualTo("SEG-1");
            assertThat(lineData.trackSegments().get(0).startMeters()).isEqualTo(0.0);
            assertThat(lineData.trackSegments().get(0).endMeters()).isEqualTo(120.0);
            assertThat(lineData.trackSegments().get(0).defaultSpeedLimitMetersPerSecond()).isEqualTo(8.0);
            assertThat(lineData.speedLimitZones()).singleElement()
                .satisfies(zone -> {
                    assertThat(zone.segmentId()).isEqualTo("SEG-1");
                    assertThat(zone.startMeters()).isEqualTo(0.0);
                    assertThat(zone.endMeters()).isEqualTo(120.0);
                    assertThat(zone.speedLimitMetersPerSecond()).isEqualTo(8.0);
                });
            assertThat(lineData.gradientZones()).singleElement()
                .satisfies(zone -> assertThat(zone.gradient()).isEqualTo(0.02));
            assertThat(lineData.platforms()).singleElement()
                .satisfies(platform -> {
                    assertThat(platform.id()).isEqualTo("PLAT-1");
                    assertThat(platform.anchorSegmentId()).isEqualTo("SEG-1");
                    assertThat(platform.centerMeters()).isEqualTo(60.0);
                });
            assertThat(lineData.stations()).singleElement()
                .satisfies(station -> {
                    assertThat(station.id()).isEqualTo("ST-1");
                    assertThat(station.platformIds()).containsExactly("PLAT-1");
                    assertThat(station.centerMeters()).isEqualTo(60.0);
                });
            assertThat(lineData.switches()).singleElement()
                .satisfies(switchDefinition -> {
                    assertThat(switchDefinition.normalSegmentId()).isEqualTo("SEG-1");
                    assertThat(switchDefinition.reverseSegmentId()).isEqualTo("SEG-2");
                    assertThat(switchDefinition.divergingSpeedLimitMetersPerSecond()).isEqualTo(6.0);
                });
            assertThat(lineData.signals()).singleElement()
                .satisfies(signal -> assertThat(signal.positionMeters()).isEqualTo(20.0));
            assertThat(lineData.balises()).singleElement()
                .satisfies(balise -> assertThat(balise.positionMeters()).isEqualTo(30.0));
            assertThat(lineData.routes()).singleElement()
                .satisfies(route -> {
                    assertThat(route.startSignalId()).isEqualTo("SIG-1");
                    assertThat(route.axleSectionIds()).containsExactly("AXLE-11");
                });
        } finally {
            Files.deleteIfExists(workbookPath);
        }
    }

    private void writeSampleWorkbook(Path outputPath) throws IOException {
        try (Workbook workbook = new HSSFWorkbook(); OutputStream outputStream = Files.newOutputStream(outputPath)) {
            writeRows(workbook.createSheet("点表"), List.<Object[]>of(
                row(4, 1, "P1", "T1", 0, 0, 0, 0, 0, 0, 0, 0, "0"),
                row(5, 2, "P2", "T1", 12000, 0, 0, 0, 0, 0, 0, 0, "0")
            ));
            writeRows(workbook.createSheet("Seg表"), List.<Object[]>of(
                row(4, 1, 12000, 2, 1, 2, 2, 65535, 65535, 2, 65535, 0, 0, 0, 0, 65535, 65535, 65535, 0, 0),
                row(5, 2, 8000, 2, 2, 3, 1, 65535, 65535, 65535, 65535, 0, 0, 0, 0, 65535, 65535, 65535, 0, 0)
            ));
            writeRows(workbook.createSheet("静态限速表"), List.<Object[]>of(
                row(4, 1, 1, 0, 12000, 65535, 800)
            ));
            writeRows(workbook.createSheet("坡度表"), List.<Object[]>of(
                row(4, 1, 1, 0, 1, 12000, 65535, 65535, 65535, 65535, 65535, 65535, 20, "0xaa", 0)
            ));
            writeRows(workbook.createSheet("站台表"), List.<Object[]>of(
                row(4, 1, "K0+100.000", 1, "0xaa", 0, 65535, 65535, 65535, 65535, 65535, 65535, "0x01", 1001)
            ));
            writeRows(workbook.createSheet("车站表"), List.<Object[]>of(
                row(4, 1, "Station-A", 1, 1)
            ));
            writeRows(workbook.createSheet("道岔表"), List.<Object[]>of(
                row(4, 1, "SW-A", 65535, "2", 1, 2, 1, 600, 3001)
            ));
            writeRows(workbook.createSheet("信号机表"), List.<Object[]>of(
                row(4, 1, "SIG-A", "1", "0x000C", 1, 2000, "0xaa", "0x31", 4001)
            ));
            writeRows(workbook.createSheet("应答器表"), List.<Object[]>of(
                row(4, 1, "0x0001", "BAL-A", 1, 3000, 5001, "1", 1, "0xaa")
            ));
            writeRows(workbook.createSheet("进路表"), List.<Object[]>of(
                row(4, 1, "ROUTE-A", "0x0001", 1, 1, 1, 11)
            ));

            workbook.write(outputStream);
        }
    }

    private void writeRows(Sheet sheet, List<Object[]> rows) {
        for (Object[] values : rows) {
            Row row = sheet.createRow((Integer) values[0]);
            for (int columnIndex = 1; columnIndex < values.length; columnIndex++) {
                Object value = values[columnIndex];
                if (value == null) {
                    continue;
                }
                if (value instanceof Number number) {
                    row.createCell(columnIndex - 1).setCellValue(number.doubleValue());
                } else {
                    row.createCell(columnIndex - 1).setCellValue(String.valueOf(value));
                }
            }
        }
    }

    private Object[] row(int rowIndex, Object... values) {
        Object[] row = new Object[values.length + 1];
        row[0] = rowIndex;
        System.arraycopy(values, 0, row, 1, values.length);
        return row;
    }
}
