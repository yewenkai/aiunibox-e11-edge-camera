"""Binary sensors for AIUniBOX-E11."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import E11RuntimeData
from .entity import E11Entity


@dataclass(frozen=True, slots=True)
class E11BinaryDescription:
    key: str
    name: str
    field: str
    device_class: BinarySensorDeviceClass


DESCRIPTIONS = (
    E11BinaryDescription(
        "stream_ready",
        "视频流",
        "streamReady",
        BinarySensorDeviceClass.CONNECTIVITY,
    ),
    E11BinaryDescription(
        "watching",
        "正在观看",
        "watching",
        BinarySensorDeviceClass.OCCUPANCY,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up E11 binary sensors."""
    async_add_entities(
        E11BinarySensor(entry.runtime_data, description)
        for description in DESCRIPTIONS
    )


class E11BinarySensor(E11Entity, BinarySensorEntity):
    """Boolean value from the device status endpoint."""

    def __init__(
        self,
        runtime: E11RuntimeData,
        description: E11BinaryDescription,
    ) -> None:
        super().__init__(runtime, description.key)
        self._description = description
        self._attr_name = description.name
        self._attr_device_class = description.device_class

    @property
    def is_on(self) -> bool:
        """Return the current boolean state."""
        return bool((self.coordinator.data or {}).get(self._description.field))
