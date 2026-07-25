"""Lights for AIUniBOX-E11."""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import (
    ATTR_BRIGHTNESS,
    ATTR_RGB_COLOR,
    ColorMode,
    LightEntity,
)
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
    """Set up fill and RGB lights."""
    runtime = entry.runtime_data
    async_add_entities([E11FillLight(runtime), E11StatusLight(runtime)])


class E11FillLight(E11Entity, LightEntity):
    """White fill light."""

    _attr_name = "补光灯"
    _attr_color_mode = ColorMode.BRIGHTNESS
    _attr_supported_color_modes = {ColorMode.BRIGHTNESS}

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "fill_light")

    @property
    def brightness(self) -> int:
        """Return current brightness."""
        return int((self.coordinator.data or {}).get("fillLight", 0))

    @property
    def is_on(self) -> bool:
        """Return whether the light is on."""
        return self.brightness > 0

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Turn the fill light on."""
        await self.runtime.client.async_fill_light(int(kwargs.get(ATTR_BRIGHTNESS, 255)))
        await self._async_refresh()

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Turn the fill light off."""
        await self.runtime.client.async_fill_light(0)
        await self._async_refresh()


class E11StatusLight(E11Entity, LightEntity):
    """Three-channel RGB status light."""

    _attr_name = "状态灯"
    _attr_color_mode = ColorMode.RGB
    _attr_supported_color_modes = {ColorMode.RGB}

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "status_light")

    @property
    def rgb_color(self) -> tuple[int, int, int]:
        """Return red, green and blue LED levels."""
        leds = (self.coordinator.data or {}).get("leds", {})
        return (
            int(leds.get("red", 0)),
            int(leds.get("green", 0)),
            int(leds.get("blue", 0)),
        )

    @property
    def brightness(self) -> int:
        """Return the strongest channel."""
        return max(self.rgb_color)

    @property
    def is_on(self) -> bool:
        """Return whether any channel is on."""
        return self.brightness > 0

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Set the RGB status light."""
        red, green, blue = kwargs.get(ATTR_RGB_COLOR, self.rgb_color or (0, 255, 0))
        brightness = int(kwargs.get(ATTR_BRIGHTNESS, max(red, green, blue, 255)))
        maximum = max(red, green, blue, 1)
        scale = brightness / maximum
        await self.runtime.client.async_status_led(
            min(255, round(red * scale)),
            min(255, round(green * scale)),
            min(255, round(blue * scale)),
        )
        await self._async_refresh()

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Turn all status LED channels off."""
        await self.runtime.client.async_status_led(0, 0, 0)
        await self._async_refresh()
