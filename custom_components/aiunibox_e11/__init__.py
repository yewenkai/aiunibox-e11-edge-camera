"""AIUniBOX-E11 Home Assistant integration."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import CONF_HOST
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed, ConfigEntryNotReady
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import E11ApiClient, E11AuthenticationError, E11ConnectionError
from .const import CONF_API_PORT, CONF_RTSP_PORT, CONF_TOKEN
from .coordinator import E11DataUpdateCoordinator

PLATFORMS = [
    "binary_sensor",
    "button",
    "camera",
    "light",
    "select",
    "sensor",
    "switch",
]


@dataclass(slots=True)
class E11RuntimeData:
    """Runtime data shared by all platforms."""

    client: E11ApiClient
    coordinator: E11DataUpdateCoordinator
    info: dict


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up AIUniBOX-E11 from a config entry."""
    client = E11ApiClient(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data[CONF_API_PORT],
        entry.data[CONF_RTSP_PORT],
        entry.data.get(CONF_TOKEN, ""),
    )
    try:
        info = await client.async_info()
        coordinator = E11DataUpdateCoordinator(hass, entry, client)
        await coordinator.async_config_entry_first_refresh()
    except E11AuthenticationError as err:
        raise ConfigEntryAuthFailed(str(err)) from err
    except E11ConnectionError as err:
        raise ConfigEntryNotReady(str(err)) from err

    entry.runtime_data = E11RuntimeData(client, coordinator, info)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_reload_entry))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload a config entry."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def _async_reload_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Reload after options or connection settings change."""
    await hass.config_entries.async_reload(entry.entry_id)
