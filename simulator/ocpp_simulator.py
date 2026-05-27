"""
OCPP 1.6J Charge Point Simulator
=================================
Simulates a real EV charger connecting to the ocpp16j Cumulocity microservice.

Usage:
    # Install dependencies (once):
    pip install -r requirements.txt

    # Run against LOCAL microservice (no auth needed):
    python ocpp_simulator.py --local

    # Run against CLOUD microservice:
    python ocpp_simulator.py
"""

import asyncio
import base64
import logging
import os
import sys
from datetime import datetime, timezone

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass  # python-dotenv is optional; export env vars manually

import websockets
from ocpp.v16 import ChargePoint as cp
from ocpp.v16 import call

logging.basicConfig(
    format="%(asctime)s [%(levelname)s] %(message)s",
    level=logging.INFO
)

# ─── CONNECTION CONFIG ─────────────────────────────────────────────────────────
# Set these via environment variables or a .env file (never hardcode credentials).
# See simulator/.env.example for reference.

# The Cumulocity managed object ID of the device (chargeBoxId in the URL).
# This must already exist in your C8Y tenant as a managed object.
DEVICE_ID = os.environ["C8Y_DEVICE_ID"]

# Cloud base URL, e.g. https://your-tenant.eu-latest.cumulocity.com
_C8Y_BASE_URL = os.environ["C8Y_BASE_URL"].rstrip("/")

# Cloud endpoint — deployed microservice via Cumulocity proxy
CLOUD_URL = f"wss://{_C8Y_BASE_URL.removeprefix('https://').removeprefix('http://')}/service/ocpp16j/ws/{DEVICE_ID}"


def _build_auth_header() -> str:
    """Build the Basic Auth header from C8Y_TENANT, C8Y_USERNAME, C8Y_PASSWORD."""
    tenant   = os.environ["C8Y_TENANT"]
    username = os.environ["C8Y_USERNAME"]
    password = os.environ["C8Y_PASSWORD"]
    token = base64.b64encode(f"{tenant}/{username}:{password}".encode()).decode()
    return f"Basic {token}"


# Local endpoint — no auth required, microservice running on your machine
LOCAL_URL = f"ws://localhost:8080/ws/{DEVICE_ID}"

# ──────────────────────────────────────────────────────────────────────────────


class ChargePointSimulator(cp):
    """
    Simulates a single charge point (EV charger) using the OCPP 1.6J protocol.

    Sends all standard charger-initiated messages and waits to receive
    commands dispatched from Cumulocity (RemoteStart, Reset, etc.).
    """

    async def send_boot_notification(self):
        """Announces the charger to the Central System (this MS)."""
        response = await self.call(call.BootNotificationPayload(
            charge_point_vendor="ACME Corp",
            charge_point_model="Simulator Pro",
            charge_point_serial_number="SIM-001",
            firmware_version="2.0.0"
        ))
        logging.info(f"[BOOT]   status={response.status}, serverTime={response.current_time}, heartbeatInterval={response.interval}s")
        return response

    async def send_heartbeat(self):
        """Confirms the charger is alive."""
        response = await self.call(call.HeartbeatPayload())
        logging.info(f"[HB]     currentTime={response.current_time}")

    async def send_status_notification(self, connector_id=1, status="Available", error_code="NoError"):
        """Reports connector status (Available, Charging, Faulted, etc.)."""
        await self.call(call.StatusNotificationPayload(
            connector_id=connector_id,
            error_code=error_code,
            status=status
        ))
        logging.info(f"[STATUS] connector={connector_id}, status={status}, error={error_code}")

    async def send_authorize(self, id_tag="RFID-TEST-001"):
        """Requests authorization for an RFID tag before starting a transaction."""
        response = await self.call(call.AuthorizePayload(id_tag=id_tag))
        logging.info(f"[AUTH]   idTag={id_tag}, result={response.id_tag_info}")
        return response

    async def send_start_transaction(self, connector_id=1, id_tag="RFID-TEST-001", meter_start=0):
        """Notifies the CS that a charging session has started. Returns the transactionId."""
        response = await self.call(call.StartTransactionPayload(
            connector_id=connector_id,
            id_tag=id_tag,
            meter_start=meter_start,
            timestamp=datetime.now(timezone.utc).isoformat()
        ))
        logging.info(f"[START]  transactionId={response.transaction_id}, status={response.id_tag_info['status']}")
        return response.transaction_id

    async def send_meter_values(self, connector_id=1, transaction_id=None,
                                energy_wh=7500, power_w=11000,
                                current_a=48.0, voltage_v=230.2):
        """Sends a batch of sampled measurements for an active session."""
        await self.call(call.MeterValuesPayload(
            connector_id=connector_id,
            transaction_id=transaction_id,
            meter_value=[{
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "sampledValue": [
                    {"value": str(energy_wh),  "measurand": "Energy.Active.Import.Register", "unit": "Wh"},
                    {"value": str(power_w),    "measurand": "Power.Active.Import",            "unit": "W"},
                    {"value": str(current_a),  "measurand": "Current.Import",                "unit": "A"},
                    {"value": str(voltage_v),  "measurand": "Voltage",                       "unit": "V"},
                ]
            }]
        ))
        logging.info(f"[METER]  energy={energy_wh}Wh, power={power_w}W, current={current_a}A, voltage={voltage_v}V")

    async def send_stop_transaction(self, transaction_id, meter_stop=42500, reason="Local"):
        """Notifies the CS that a charging session has ended."""
        await self.call(call.StopTransactionPayload(
            transaction_id=transaction_id,
            meter_stop=meter_stop,
            timestamp=datetime.now(timezone.utc).isoformat(),
            reason=reason
        ))
        logging.info(f"[STOP]   transactionId={transaction_id}, meterStop={meter_stop}Wh, reason={reason}")

    async def simulate_full_session(self):
        """
        Runs a complete realistic charging session:
          1. Boot → register with Central System
          2. Status → Available
          3. Authorize → check RFID tag
          4. StartTransaction → begin charging session
          5. MeterValues x 3 → send telemetry every 5s
          6. StopTransaction → end session
          7. Status → back to Available
          8. Stay alive 60s so you can fire C8Y operations and observe them
        """
        logging.info("=== Starting full charge session simulation ===")

        boot = await self.send_boot_notification()
        if boot.status != "Accepted":
            logging.error("Boot rejected by Central System — aborting")
            return
        await asyncio.sleep(1)

        await self.send_status_notification(status="Available")
        await asyncio.sleep(1)

        await self.send_authorize(id_tag="RFID-TEST-001")
        await asyncio.sleep(1)

        await self.send_status_notification(status="Preparing")
        await asyncio.sleep(1)

        tx_id = await self.send_start_transaction(connector_id=1, id_tag="RFID-TEST-001", meter_start=0)
        await self.send_status_notification(status="Charging")

        # Simulate 3 meter value intervals (energy increasing over time)
        for i in range(1, 4):
            await asyncio.sleep(5)
            await self.send_meter_values(
                transaction_id=tx_id,
                energy_wh=i * 7500,
                power_w=11000,
                current_a=48.0,
                voltage_v=230.2
            )

        await self.send_stop_transaction(transaction_id=tx_id, meter_stop=42500)
        await self.send_status_notification(status="Finishing")
        await asyncio.sleep(1)
        await self.send_status_notification(status="Available")

        logging.info("=== Session complete. Staying connected 60s — fire C8Y operations now! ===")
        logging.info("    E.g.: POST /devicecontrol/operations { 'deviceId': '%s', 'c8y_Restart': {'type': 'Soft'} }", self.id)
        await asyncio.sleep(60)

        logging.info("=== Simulator done ===")


async def main():
    use_local = "--local" in sys.argv

    if use_local:
        url = LOCAL_URL
        headers = None
        logging.info(f"Connecting to LOCAL MS: {url}")
    else:
        url = CLOUD_URL
        headers = [("Authorization", _build_auth_header())]
        logging.info(f"Connecting to CLOUD MS: {url}")

    async with websockets.connect(
        url,
        extra_headers=headers,
        subprotocols=["ocpp1.6"],
        open_timeout=30
    ) as ws:
        logging.info("WebSocket connected!")
        charge_point = ChargePointSimulator(DEVICE_ID, ws)

        # Start the message receive loop (handles incoming commands from MS)
        asyncio.ensure_future(charge_point.start())

        await charge_point.simulate_full_session()


if __name__ == "__main__":
    asyncio.run(main())
