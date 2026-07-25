"""Config flow for AIUniBOX-E11."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant import config_entries
from homeassistant.const import CONF_HOST, CONF_NAME
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .api import (
    E11ApiClient,
    E11ApiError,
    E11AuthenticationError,
    E11ConnectionError,
)
from .const import (
    CONF_API_PORT,
    CONF_RTSP_PORT,
    CONF_TOKEN,
    DEFAULT_API_PORT,
    DEFAULT_NAME,
    DEFAULT_RTSP_PORT,
    DOMAIN,
)


def _schema(defaults: dict[str, Any] | None = None) -> vol.Schema:
    values = defaults or {}
    return vol.Schema(
        {
            vol.Required(CONF_HOST, default=values.get(CONF_HOST, "")): str,
            vol.Required(
                CONF_API_PORT,
                default=values.get(CONF_API_PORT, DEFAULT_API_PORT),
            ): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
            vol.Required(
                CONF_RTSP_PORT,
                default=values.get(CONF_RTSP_PORT, DEFAULT_RTSP_PORT),
            ): vol.All(vol.Coerce(int), vol.Range(min=1, max=65535)),
            vol.Optional(CONF_TOKEN, default=values.get(CONF_TOKEN, "")): str,
            vol.Optional(CONF_NAME, default=values.get(CONF_NAME, DEFAULT_NAME)): str,
        }
    )


class E11ConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle setup through the Home Assistant UI."""

    VERSION = 1

    async def _validate(self, data: dict[str, Any]) -> dict[str, Any]:
        client = E11ApiClient(
            async_get_clientsession(self.hass),
            data[CONF_HOST].strip(),
            data[CONF_API_PORT],
            data[CONF_RTSP_PORT],
            data.get(CONF_TOKEN, "").strip(),
        )
        info = await client.async_info()
        await client.async_status()
        return info

    async def async_step_user(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> FlowResult:
        """Configure a new device."""
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                info = await self._validate(user_input)
            except E11AuthenticationError:
                errors["base"] = "invalid_auth"
            except (E11ConnectionError, E11ApiError):
                errors["base"] = "cannot_connect"
            else:
                device_id = str(info.get("deviceId", user_input[CONF_HOST]))
                await self.async_set_unique_id(device_id)
                self._abort_if_unique_id_configured()
                data = dict(user_input)
                data[CONF_HOST] = data[CONF_HOST].strip()
                data[CONF_TOKEN] = data.get(CONF_TOKEN, "").strip()
                title = data.pop(CONF_NAME, DEFAULT_NAME).strip() or DEFAULT_NAME
                return self.async_create_entry(title=title, data=data)

        return self.async_show_form(
            step_id="user",
            data_schema=_schema(user_input),
            errors=errors,
        )

    async def async_step_reauth(
        self,
        entry_data: dict[str, Any],
    ) -> FlowResult:
        """Start token replacement."""
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> FlowResult:
        """Replace an invalid token."""
        entry = self._get_reauth_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            updated = {**entry.data, CONF_TOKEN: user_input.get(CONF_TOKEN, "").strip()}
            try:
                await self._validate(updated)
            except E11AuthenticationError:
                errors["base"] = "invalid_auth"
            except (E11ConnectionError, E11ApiError):
                errors["base"] = "cannot_connect"
            else:
                return self.async_update_reload_and_abort(entry, data=updated)

        return self.async_show_form(
            step_id="reauth_confirm",
            data_schema=vol.Schema({vol.Required(CONF_TOKEN): str}),
            errors=errors,
        )

    async def async_step_reconfigure(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> FlowResult:
        """Change host, ports or token."""
        entry = self._get_reconfigure_entry()
        errors: dict[str, str] = {}
        if user_input is not None:
            try:
                info = await self._validate(user_input)
            except E11AuthenticationError:
                errors["base"] = "invalid_auth"
            except (E11ConnectionError, E11ApiError):
                errors["base"] = "cannot_connect"
            else:
                await self.async_set_unique_id(str(info.get("deviceId", user_input[CONF_HOST])))
                self._abort_if_unique_id_mismatch()
                data = dict(user_input)
                data[CONF_HOST] = data[CONF_HOST].strip()
                data[CONF_TOKEN] = data.get(CONF_TOKEN, "").strip()
                title = data.pop(CONF_NAME, entry.title).strip() or entry.title
                self.hass.config_entries.async_update_entry(entry, title=title)
                return self.async_update_reload_and_abort(entry, data=data)

        defaults = {**entry.data, CONF_NAME: entry.title}
        return self.async_show_form(
            step_id="reconfigure",
            data_schema=_schema(defaults if user_input is None else user_input),
            errors=errors,
        )
