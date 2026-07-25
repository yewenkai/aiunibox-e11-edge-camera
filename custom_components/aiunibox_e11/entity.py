"""Shared entity base for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from . import E11RuntimeData
from .const import DOMAIN


class E11Entity(CoordinatorEntity):
    """Base entity backed by the status coordinator."""

    _attr_has_entity_name = True

    def __init__(self, runtime: E11RuntimeData, key: str) -> None:
        super().__init__(runtime.coordinator)
        self.runtime = runtime
        device_id = str(runtime.info.get("deviceId", runtime.client.host))
        self._attr_unique_id = f"{device_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, device_id)},
            manufacturer="AIUniBOX",
            model=str(runtime.info.get("model", "AIUniBOX-E11")),
            name="AIUniBOX-E11 Edge Camera",
            sw_version=str(runtime.info.get("appVersion", "unknown")),
            configuration_url=runtime.client.base_url,
        )

    async def _async_refresh(self) -> None:
        await self.runtime.coordinator.async_request_refresh()
