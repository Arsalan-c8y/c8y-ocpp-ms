package com.c8y.ocpp16j.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cumulocity.model.idtype.GId;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.sdk.client.measurement.MeasurementApi;

/**
 * Processes OCPP MeterValues messages and creates Cumulocity measurements.
 *
 * Each MeterValues message can contain multiple meterValue entries (timestamps),
 * and each meterValue can contain multiple sampledValue entries (measurands).
 * This service iterates through all of them and creates typed measurements in C8Y:
 *
 *   OCPP Measurand                     → C8Y Measurement Type     (series, unit)
 *   ─────────────────────────────────────────────────────────────────────────────
 *   Energy.Active.Import.Register      → c8y_EnergyMeasurement    (E, kWh)
 *   Power.Active.Import                → c8y_PowerMeasurement     (P, kW)
 *   Current.Import                     → c8y_CurrentMeasurement   (I, A)
 *   Voltage                            → c8y_VoltageMeasurement   (V, V)
 *   Temperature                        → c8y_TemperatureMeasurement (T, °C)
 *   SoC                                → c8y_Battery              (level, %)
 *
 * Unit conversion is applied where needed (e.g. Wh → kWh, W → kW).
 */
@Service
public class MeasurementService {

    private static final Logger log = LoggerFactory.getLogger(MeasurementService.class);

    private final MeasurementApi measurementApi;

    public MeasurementService(MeasurementApi measurementApi) {
        this.measurementApi = measurementApi;
    }

    @SuppressWarnings("unchecked")
    public void processMeterValues(String chargeBoxId, Map<String, Object> meterValuesPayload) {
        try {
            List<Map<String, Object>> meterValues = (List<Map<String, Object>>) meterValuesPayload.get("meterValue");
            if (meterValues == null || meterValues.isEmpty()) {
                log.warn("No meterValue array in payload for chargeBoxId={}", chargeBoxId);
                return;
            }

            log.info("Processing {} MeterValues for chargeBoxId={}", meterValues.size(), chargeBoxId);

            for (Map<String, Object> meterValue : meterValues) {
                List<Map<String, Object>> sampledValues = (List<Map<String, Object>>) meterValue.get("sampledValue");
                if (sampledValues == null) continue;

                for (Map<String, Object> sample : sampledValues) {
                    processSampledValue(chargeBoxId, sample);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process MeterValues for chargeBoxId={}: {}", chargeBoxId, e.getMessage(), e);
        }
    }

    private void processSampledValue(String chargeBoxId, Map<String, Object> sample) {
        String measurand = (String) sample.getOrDefault("measurand", "Energy.Active.Import.Register");
        String valueStr = (String) sample.get("value");
        String unit = (String) sample.getOrDefault("unit", "");

        if (valueStr == null) return;
        double rawValue = Double.parseDouble(valueStr);

        if ("Energy.Active.Import.Register".equals(measurand)) {
            createEnergyMeasurement(chargeBoxId, rawValue, unit);
        } else if ("Power.Active.Import".equals(measurand)) {
            createPowerMeasurement(chargeBoxId, rawValue, unit);
        } else if ("Current.Import".equals(measurand)) {
            createCurrentMeasurement(chargeBoxId, rawValue, unit);
        } else if ("Voltage".equals(measurand)) {
            createVoltageMeasurement(chargeBoxId, rawValue, unit);
        } else if ("Temperature".equals(measurand)) {
            createTemperatureMeasurement(chargeBoxId, rawValue, unit);
        } else if ("SoC".equals(measurand)) {
            createSocMeasurement(chargeBoxId, rawValue);
        } else {
            log.debug("Unhandled measurand '{}' for chargeBoxId={}", measurand, chargeBoxId);
        }
    }

    private void createEnergyMeasurement(String chargeBoxId, double rawValue, String unit) {
        double kWh = "Wh".equalsIgnoreCase(unit) ? rawValue / 1000.0 : rawValue;
        createMeasurement(chargeBoxId, "c8y_EnergyMeasurement", "E", kWh, "kWh");
    }

    private void createPowerMeasurement(String chargeBoxId, double rawValue, String unit) {
        double kW = "W".equalsIgnoreCase(unit) ? rawValue / 1000.0 : rawValue;
        createMeasurement(chargeBoxId, "c8y_PowerMeasurement", "P", kW, "kW");
    }

    private void createCurrentMeasurement(String chargeBoxId, double rawValue, String unit) {
        createMeasurement(chargeBoxId, "c8y_CurrentMeasurement", "I", rawValue, "A");
    }

    private void createVoltageMeasurement(String chargeBoxId, double rawValue, String unit) {
        createMeasurement(chargeBoxId, "c8y_VoltageMeasurement", "V", rawValue, "V");
    }

    private void createTemperatureMeasurement(String chargeBoxId, double rawValue, String unit) {
        createMeasurement(chargeBoxId, "c8y_TemperatureMeasurement", "T", rawValue, "°C");
    }

    private void createSocMeasurement(String chargeBoxId, double rawValue) {
        createMeasurement(chargeBoxId, "c8y_Battery", "level", rawValue, "%");
    }

    private void createMeasurement(String chargeBoxId, String type, String series, double value, String unit) {
        MeasurementRepresentation measurement = new MeasurementRepresentation();
        measurement.setSource(asSource(chargeBoxId));
        measurement.setType(type);
        measurement.setDateTime(DateTime.now());

        Map<String, Object> fragment = new HashMap<>();
        fragment.put(series, Map.of("value", BigDecimal.valueOf(value), "unit", unit));
        measurement.setProperty(type, fragment);

        measurementApi.create(measurement);
        log.info("Created {} for chargeBoxId={}: {}={} {}", type, chargeBoxId, series, value, unit);
    }

    private com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation asSource(String chargeBoxId) {
        var source = new com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation();
        source.setId(GId.asGId(chargeBoxId));
        return source;
    }
}
