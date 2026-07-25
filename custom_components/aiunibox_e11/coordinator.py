"""Data coordinator for AIUniBOX-E11."""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import (
    DataUpdateCoordinator,
    UpdateFailed,
)

from .api import (
    E11ApiClient,
    E11ApiError,
    E11AuthenticationError,
    E11ConnectionError,
)
from .const import DOMAIN, UPDATE_INTERVAL

_LOGGER = logging.getLogger(__name__)


class E11DataUpdateCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Poll the compact device status endpoint."""

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        client: E11ApiClient,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=f"{DOMAIN}_{entry.entry_id}",
            update_interval=UPDATE_INTERVAL,
            config_entry=entry,
        )
        self.client = client

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.client.async_status()
        except E11AuthenticationError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except (E11ConnectionError, E11ApiError) as err:
            raise UpdateFailed(str(err)) from err
