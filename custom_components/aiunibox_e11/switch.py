"""Switches for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.components.switch import SwitchEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import E11RuntimeData
from .entity import E11Entity


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up privacy and IR-CUT switches."""
    runtime = entry.runtime_data
    async_add_entities([E11PrivacySwitch(runtime), E11IrcutSwitch(runtime)])


class E11PrivacySwitch(E11Entity, SwitchEntity):
    """Stop local camera capture without stopping device control."""

    _attr_name = "隐私模式"
    _attr_icon = "mdi:cctv-off"

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "privacy")

    @property
    def is_on(self) -> bool:
        """Return privacy state."""
        return bool((self.coordinator.data or {}).get("privacy"))

    async def async_turn_on(self, **kwargs) -> None:
        """Enable privacy mode."""
        await self.runtime.client.async_privacy(True)
        await self._async_refresh()

    async def async_turn_off(self, **kwargs) -> None:
        """Disable privacy mode."""
        await self.runtime.client.async_privacy(False)
        await self._async_refresh()


class E11IrcutSwitch(E11Entity, SwitchEntity):
    """Control the physical IR-CUT filter."""

    _attr_name = "IR-CUT 夜视"
    _attr_icon = "mdi:weather-night"

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "ircut")

    @property
    def is_on(self) -> bool:
        """Return filter state."""
        return bool((self.coordinator.data or {}).get("ircut"))

    async def async_turn_on(self, **kwargs) -> None:
        """Switch to infrared optical path."""
        await self.runtime.client.async_ircut(True)
        await self._async_refresh()

    async def async_turn_off(self, **kwargs) -> None:
        """Switch to daylight optical path."""
        await self.runtime.client.async_ircut(False)
        await self._async_refresh()
