"""Diagnostic sensors for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import DEGREE, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import E11RuntimeData
from .entity import E11Entity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up position and uptime sensors."""
    runtime = entry.runtime_data
    async_add_entities([E11PanPosition(runtime), E11Uptime(runtime)])


class E11PanPosition(E11Entity, SensorEntity):
    """Software-tracked pan angle."""

    _attr_name = "云台软件角度"
    _attr_icon = "mdi:rotate-360"
    _attr_native_unit_of_measurement = DEGREE

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "pan_position")

    @property
    def native_value(self) -> float:
        """Return position relative to the software zero."""
        return round(float((self.coordinator.data or {}).get("positionDegrees", 0)), 1)


class E11Uptime(E11Entity, SensorEntity):
    """App service uptime."""

    _attr_name = "运行时间"
    _attr_icon = "mdi:timer-outline"
    _attr_native_unit_of_measurement = UnitOfTime.SECONDS
    _attr_entity_category = EntityCategory.DIAGNOSTIC

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "uptime")

    @property
    def native_value(self) -> int:
        """Return service uptime in seconds."""
        return int((self.coordinator.data or {}).get("uptimeSeconds", 0))
