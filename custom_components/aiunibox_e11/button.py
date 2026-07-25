"""Buttons for AIUniBOX-E11."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity import EntityCategory
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import E11RuntimeData
from .const import DEFAULT_SPEED, DEFAULT_STEP_ANGLE
from .entity import E11Entity


@dataclass(frozen=True, slots=True)
class E11ButtonDescription:
    key: str
    name: str
    action: str
    icon: str
    category: EntityCategory | None = None


DESCRIPTIONS = (
    E11ButtonDescription("pan_left", "画面向左", "pan_left", "mdi:pan-left"),
    E11ButtonDescription("pan_right", "画面向右", "pan_right", "mdi:pan-right"),
    E11ButtonDescription("go_home", "回到常用位置", "go_home", "mdi:home-map-marker"),
    E11ButtonDescription(
        "save_home",
        "保存当前位置",
        "save_home",
        "mdi:content-save",
        EntityCategory.CONFIG,
    ),
    E11ButtonDescription(
        "soft_zero",
        "当前位置设为零点",
        "soft_zero",
        "mdi:axis-z-rotate-clockwise",
        EntityCategory.CONFIG,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up E11 buttons."""
    async_add_entities(
        E11Button(entry.runtime_data, description) for description in DESCRIPTIONS
    )


class E11Button(E11Entity, ButtonEntity):
    """Momentary device command."""

    def __init__(
        self,
        runtime: E11RuntimeData,
        description: E11ButtonDescription,
    ) -> None:
        super().__init__(runtime, description.key)
        self.entity_description = description
        self._attr_name = description.name
        self._attr_icon = description.icon
        self._attr_entity_category = description.category

    async def async_press(self) -> None:
        """Execute the associated command."""
        action = self.entity_description.action
        steps = round(DEFAULT_STEP_ANGLE * 2986 / 360)
        if action == "pan_left":
            await self.runtime.client.async_motor("left", steps, DEFAULT_SPEED)
        elif action == "pan_right":
            await self.runtime.client.async_motor("right", steps, DEFAULT_SPEED)
        elif action == "go_home":
            await self.runtime.client.async_preset("goto", "home")
        elif action == "save_home":
            await self.runtime.client.async_preset("save", "home")
        elif action == "soft_zero":
            await self.runtime.client.async_soft_zero()
        await self._async_refresh()
