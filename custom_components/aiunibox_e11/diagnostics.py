"""Diagnostics support for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.redact import async_redact_data

TO_REDACT = {"token", "ip", "host"}


async def async_get_config_entry_diagnostics(
    hass: HomeAssistant,
    entry: ConfigEntry,
) -> dict:
    """Return redacted configuration and live state."""
    return {
        "config": async_redact_data(dict(entry.data), TO_REDACT),
        "device_info": entry.runtime_data.info,
        "status": async_redact_data(entry.runtime_data.coordinator.data, TO_REDACT),
    }
