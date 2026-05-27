package com.c8y.ocpp16j.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.sdk.client.measurement.MeasurementApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MeasurementServiceTest {

    @Mock
    private MeasurementApi measurementApi;

    private MeasurementService service;

    @BeforeEach
    void setUp() {
        service = new MeasurementService(measurementApi);
    }

    @Nested
    @DisplayName("Energy Measurements")
    class EnergyTests {

        @Test
        @DisplayName("should create energy measurement from Wh value")
        void shouldConvertWhToKwh() {
            Map<String, Object> payload = meterValuesPayload(
                    "Energy.Active.Import.Register", "42500", "Wh");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            MeasurementRepresentation m = captor.getValue();
            assertThat(m.getType()).isEqualTo("c8y_EnergyMeasurement");
            assertThat(m.getSource().getId().getValue()).isEqualTo("device-001");

            @SuppressWarnings("unchecked")
            Map<String, Object> fragment = (Map<String, Object>) m.getProperty("c8y_EnergyMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) fragment.get("E");
            assertThat(series.get("value")).isEqualTo(BigDecimal.valueOf(42.5));
            assertThat(series.get("unit")).isEqualTo("kWh");
        }

        @Test
        @DisplayName("should create energy measurement from kWh value directly")
        void shouldHandleKwhDirectly() {
            Map<String, Object> payload = meterValuesPayload(
                    "Energy.Active.Import.Register", "15.3", "kWh");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            @SuppressWarnings("unchecked")
            Map<String, Object> fragment = (Map<String, Object>) captor.getValue().getProperty("c8y_EnergyMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) fragment.get("E");
            assertThat(series.get("value")).isEqualTo(BigDecimal.valueOf(15.3));
        }
    }

    @Nested
    @DisplayName("Power Measurements")
    class PowerTests {

        @Test
        @DisplayName("should create power measurement from W value")
        void shouldConvertWToKw() {
            Map<String, Object> payload = meterValuesPayload("Power.Active.Import", "7200", "W");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            MeasurementRepresentation m = captor.getValue();
            assertThat(m.getType()).isEqualTo("c8y_PowerMeasurement");

            @SuppressWarnings("unchecked")
            Map<String, Object> fragment = (Map<String, Object>) m.getProperty("c8y_PowerMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) fragment.get("P");
            assertThat(series.get("value")).isEqualTo(BigDecimal.valueOf(7.2));
            assertThat(series.get("unit")).isEqualTo("kW");
        }
    }

    @Nested
    @DisplayName("Current Measurements")
    class CurrentTests {

        @Test
        @DisplayName("should create current measurement")
        void shouldCreateCurrentMeasurement() {
            Map<String, Object> payload = meterValuesPayload("Current.Import", "32.5", "A");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            MeasurementRepresentation m = captor.getValue();
            assertThat(m.getType()).isEqualTo("c8y_CurrentMeasurement");

            @SuppressWarnings("unchecked")
            Map<String, Object> fragment = (Map<String, Object>) m.getProperty("c8y_CurrentMeasurement");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) fragment.get("I");
            assertThat(series.get("value")).isEqualTo(BigDecimal.valueOf(32.5));
            assertThat(series.get("unit")).isEqualTo("A");
        }
    }

    @Nested
    @DisplayName("Voltage Measurements")
    class VoltageTests {

        @Test
        @DisplayName("should create voltage measurement")
        void shouldCreateVoltageMeasurement() {
            Map<String, Object> payload = meterValuesPayload("Voltage", "230.1", "V");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            assertThat(captor.getValue().getType()).isEqualTo("c8y_VoltageMeasurement");
        }
    }

    @Nested
    @DisplayName("SoC Measurements")
    class SocTests {

        @Test
        @DisplayName("should create battery SoC measurement")
        void shouldCreateSocMeasurement() {
            Map<String, Object> payload = meterValuesPayload("SoC", "78", "%");

            service.processMeterValues("device-001", payload);

            ArgumentCaptor<MeasurementRepresentation> captor = ArgumentCaptor.forClass(MeasurementRepresentation.class);
            verify(measurementApi).create(captor.capture());

            MeasurementRepresentation m = captor.getValue();
            assertThat(m.getType()).isEqualTo("c8y_Battery");

            @SuppressWarnings("unchecked")
            Map<String, Object> fragment = (Map<String, Object>) m.getProperty("c8y_Battery");
            @SuppressWarnings("unchecked")
            Map<String, Object> series = (Map<String, Object>) fragment.get("level");
            assertThat(series.get("value")).isEqualTo(BigDecimal.valueOf(78.0));
            assertThat(series.get("unit")).isEqualTo("%");
        }
    }

    @Nested
    @DisplayName("Multiple Sampled Values")
    class MultiValueTests {

        @Test
        @DisplayName("should create measurements for all sampled values in one MeterValues message")
        void shouldHandleMultipleSampledValues() {
            Map<String, Object> payload = Map.of(
                    "connectorId", 1,
                    "meterValue", List.of(Map.of(
                            "timestamp", "2026-05-26T12:00:00Z",
                            "sampledValue", List.of(
                                    Map.of("value", "42500", "measurand", "Energy.Active.Import.Register", "unit", "Wh"),
                                    Map.of("value", "7200", "measurand", "Power.Active.Import", "unit", "W"),
                                    Map.of("value", "32", "measurand", "Current.Import", "unit", "A"),
                                    Map.of("value", "230", "measurand", "Voltage", "unit", "V")
                            )
                    ))
            );

            service.processMeterValues("device-001", payload);

            verify(measurementApi, times(4)).create(any(MeasurementRepresentation.class));
        }

        @Test
        @DisplayName("should handle empty meterValue array gracefully")
        void shouldHandleEmptyMeterValues() {
            Map<String, Object> payload = Map.of("connectorId", 1, "meterValue", List.of());

            service.processMeterValues("device-001", payload);

            verify(measurementApi, never()).create(any());
        }
    }

    // Helper to build a standard MeterValues payload
    private Map<String, Object> meterValuesPayload(String measurand, String value, String unit) {
        return Map.of(
                "connectorId", 1,
                "meterValue", List.of(Map.of(
                        "timestamp", "2026-05-26T12:00:00Z",
                        "sampledValue", List.of(
                                Map.of("value", value, "measurand", measurand, "unit", unit)
                        )
                ))
        );
    }
}
