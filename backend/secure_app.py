"""Production entrypoint for MAI backend.

Imports the existing processing application, replaces its legacy shared-token authenticator
with per-device session authentication, and adds persistent resumable meeting jobs.
"""

import app as core
from device_auth import require_auth, router as auth_router
from jobs import init_job_system, router as jobs_router

# Existing endpoint functions resolve require_auth from the app module at call time, so this
# assignment upgrades legacy endpoints without duplicating their stable behavior.
core.require_auth = require_auth
core.app.include_router(auth_router)
core.app.include_router(jobs_router)

init_job_system()

app = core.app
