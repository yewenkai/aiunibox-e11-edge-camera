"""Camera platform for AIUniBOX-E11."""

from __future__ import annotations

from homeassistant.components.camera import Camera, CameraEntityFeature
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
    """Set up the E11 camera."""
    async_add_entities([E11Camera(entry.runtime_data)])


class E11Camera(E11Entity, Camera):
    """Live camera with authenticated snapshots and RTSP streaming."""

    _attr_name = "摄像头"
    _attr_supported_features = CameraEntityFeature.STREAM
    _attr_use_stream_for_stills = False

    def __init__(self, runtime: E11RuntimeData) -> None:
        Camera.__init__(self)
        super().__init__(runtime, "camera")

    @property
    def is_streaming(self) -> bool:
        """Return whether the stream is healthy."""
        data = self.coordinator.data or {}
        return bool(data.get("rtspPublishing") and not data.get("privacy"))

    async def async_camera_image(
        self,
        width: int | None = None,
        height: int | None = None,
    ) -> bytes | None:
        """Return one JPEG snapshot."""
        return await self.runtime.client.async_snapshot()

    async def stream_source(self) -> str | None:
        """Return an FFmpeg-compatible RTSP source."""
        if (self.coordinator.data or {}).get("privacy"):
            return None
        return self.runtime.client.rtsp_url
