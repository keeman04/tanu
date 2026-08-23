"""Production entrypoint for MAI backend.

Imports the existing processing application, replaces its legacy shared-token authenticator
with per-device session authentication, and adds persistent resumable meeting jobs.
"""

import app as core
import jobs
from device_auth import require_auth, router as auth_router
from job_cleanup import cleanup_once, start_cleanup_loop
from job_segmentation import segment_audio
from jobs import init_job_system, router as jobs_router
from realtime import router as realtime_router

# Existing endpoint functions resolve require_auth from the app module at call time, so this
# assignment upgrades legacy endpoints without duplicating their stable behavior.
core.require_auth = require_auth
# V1.4 prefers a real silence near the ~7 minute target, retaining 5 seconds of overlap.
# If silence detection fails, job_segmentation deterministically falls back to time boundaries.
jobs._segment_audio = segment_audio
core.app.include_router(auth_router)
core.app.include_router(jobs_router)
core.app.include_router(realtime_router)

init_job_system()
cleanup_once()
start_cleanup_loop()

app = core.app
