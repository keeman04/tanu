"""Production entrypoint for MAI backend.

Imports the existing processing application, replaces its legacy shared-token authenticator
with per-device session authentication, and adds enrollment/session/revocation endpoints.
"""

import app as core
from device_auth import require_auth, router

# Existing endpoint functions resolve require_auth from the app module at call time, so this
# assignment upgrades /v1/realtime/client-secret and /v1/meetings/process without duplicating
# the stable transcription/MOM implementation.
core.require_auth = require_auth
core.app.include_router(router)

app = core.app
