"""Async HTTP client for AIUniBOX-E11."""

from __future__ import annotations

from typing import Any

import aiohttp


class E11ApiError(Exception):
    """Base API error."""


class E11AuthenticationError(E11ApiError):
    """API token was rejected."""


class E11ConnectionError(E11ApiError):
    """Device could not be reached."""


class E11ApiClient:
    """Small async client for the device-local API."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        api_port: int,
        rtsp_port: int,
        token: str,
    ) -> None:
        self._session = session
        self.host = host
        self.api_port = api_port
        self.rtsp_port = rtsp_port
        self.token = token

    @property
    def base_url(self) -> str:
        """Return device HTTP base URL."""
        return f"http://{self.host}:{self.api_port}"

    @property
    def rtsp_url(self) -> str:
        """Return RTSP stream URL."""
        return f"rtsp://{self.host}:{self.rtsp_port}/cam"

    @property
    def headers(self) -> dict[str, str]:
        """Return request authorization headers."""
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    async def _request(
        self,
        path: str,
        params: dict[str, Any] | None = None,
        *,
        binary: bool = False,
    ) -> Any:
        try:
            timeout = aiohttp.ClientTimeout(total=10)
            async with self._session.get(
                f"{self.base_url}{path}",
                params=params,
                headers=self.headers,
                timeout=timeout,
            ) as response:
                if response.status == 401:
                    raise E11AuthenticationError("API Token 无效")
                if response.status >= 400:
                    raise E11ApiError(
                        f"设备返回 HTTP {response.status}: {await response.text()}"
                    )
                if binary:
                    return await response.read()
                payload = await response.json(content_type=None)
                if payload.get("ok") is False:
                    raise E11ApiError(payload.get("error", "设备拒绝请求"))
                return payload
        except E11ApiError:
            raise
        except (aiohttp.ClientError, TimeoutError) as err:
            raise E11ConnectionError(str(err)) from err

    async def async_info(self) -> dict[str, Any]:
        """Read public device identity."""
        return await self._request("/api/info")

    async def async_status(self) -> dict[str, Any]:
        """Read current device state."""
        return await self._request("/api/status")

    async def async_snapshot(self) -> bytes:
        """Fetch one JPEG frame."""
        return await self._request("/snapshot", binary=True)

    async def async_motor(self, direction: str, steps: int, speed: int) -> None:
        """Move the pan motor."""
        await self._request(
            "/api/motor",
            {"dir": direction, "steps": steps, "speed": speed},
        )

    async def async_fill_light(self, level: int) -> None:
        """Set fill light brightness."""
        await self._request("/api/filllight", {"level": level})

    async def async_status_led(self, red: int, green: int, blue: int) -> None:
        """Set RGB status LEDs."""
        await self._request(
            "/api/statusled",
            {"r": red, "g": green, "b": blue},
        )

    async def async_scene(self, mode: int) -> None:
        """Set camera day/night scene mode."""
        await self._request("/api/scene", {"mode": mode})

    async def async_ircut(self, enabled: bool) -> None:
        """Set IR-CUT mode."""
        await self._request("/api/ircut", {"on": str(enabled).lower()})

    async def async_privacy(self, enabled: bool) -> None:
        """Enable or disable privacy mode."""
        await self._request("/api/privacy", {"on": str(enabled).lower()})

    async def async_preset(self, action: str, name: str = "home") -> None:
        """Save or move to a software preset."""
        await self._request("/api/preset", {"action": action, "name": name})

    async def async_soft_zero(self) -> None:
        """Declare the current pan angle as software zero."""
        await self._request("/api/position", {"action": "zero"})
