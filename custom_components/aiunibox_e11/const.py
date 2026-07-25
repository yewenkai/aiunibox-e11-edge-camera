"""Constants for the AIUniBOX-E11 integration."""

from datetime import timedelta

DOMAIN = "aiunibox_e11"
DEFAULT_NAME = "联通 E11 中屏"
DEFAULT_API_PORT = 8080
DEFAULT_RTSP_PORT = 8554
DEFAULT_STEP_ANGLE = 10
DEFAULT_SPEED = 400
UPDATE_INTERVAL = timedelta(seconds=5)

CONF_API_PORT = "api_port"
CONF_RTSP_PORT = "rtsp_port"
CONF_TOKEN = "token"
