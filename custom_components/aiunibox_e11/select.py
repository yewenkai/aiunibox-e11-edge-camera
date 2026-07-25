"""Scene selector for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback

from . import E11RuntimeData
from .entity import E11Entity

OPTIONS = ("日间", "夜间", "自动")


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the scene selector."""
    async_add_entities([E11SceneSelect(entry.runtime_data)])


class E11SceneSelect(E11Entity, SelectEntity):
    """Select day, night or automatic scene mode."""

    _attr_name = "夜视场景"
    _attr_options = list(OPTIONS)
    _attr_icon = "mdi:theme-light-dark"

    def __init__(self, runtime: E11RuntimeData) -> None:
        super().__init__(runtime, "scene")

    @property
    def current_option(self) -> str:
        """Return current scene name."""
        mode = int((self.coordinator.data or {}).get("sceneMode", 2))
        return OPTIONS[max(0, min(2, mode))]

    async def async_select_option(self, option: str) -> None:
        """Apply scene mode."""
        await self.runtime.client.async_scene(OPTIONS.index(option))
        await self._async_refresh()
